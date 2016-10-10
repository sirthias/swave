/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import scala.annotation.tailrec
import scala.concurrent.duration._
import com.typesafe.scalalogging.Logger
import swave.core.impl.stages.drain.SubDrainStage
import swave.core.impl.stages.inout.AsyncBoundaryStage
import swave.core.impl.stages.spout.SubSpoutStage
import swave.core.impl.stages.Stage
import swave.core.util._
import swave.core._

private[core] final class StreamRunner(_throughput: Int, _log: Logger, _dispatcher: Dispatcher, scheduler: Scheduler)
    extends StreamActor(_throughput, _log, _dispatcher) {
  import StreamRunner._

  protected type MessageType = Message

  startMessageProcessing()

  def enqueueRequest(target: Stage, n: Long)(implicit from: Outport): Unit =
    enqueue(new StreamRunner.Message.Request(target, n, from))
  def enqueueCancel(target: Stage)(implicit from: Outport): Unit =
    enqueue(new StreamRunner.Message.Cancel(target, from))

  def enqueueOnNext(target: Stage, elem: AnyRef)(implicit from: Inport): Unit =
    enqueue(new StreamRunner.Message.OnNext(target, elem, from))
  def enqueueOnComplete(target: Stage)(implicit from: Inport): Unit =
    enqueue(new StreamRunner.Message.OnComplete(target, from))
  def enqueueOnError(target: Stage, e: Throwable)(implicit from: Inport): Unit =
    enqueue(new StreamRunner.Message.OnError(target, e, from))

  def enqueueXStart(target: Stage): Unit =
    enqueue(new StreamRunner.Message.XStart(target))
  def enqueueXEvent(target: Stage, ev: AnyRef): Unit =
    enqueue(new StreamRunner.Message.XEvent(target, ev, this))

  protected def receive(msg: Message): Unit = {
    val target = msg.target
    msg.id match {
      case 0 ⇒
        val m = msg.asInstanceOf[Message.Subscribe]
        target.subscribe()(m.from)
      case 1 ⇒
        val m = msg.asInstanceOf[Message.Request]
        target.request(m.n)(m.from)
      case 2 ⇒
        val m = msg.asInstanceOf[Message.Cancel]
        target.cancel()(m.from)
      case 3 ⇒
        val m = msg.asInstanceOf[Message.OnSubscribe]
        target.onSubscribe()(m.from)
      case 4 ⇒
        val m = msg.asInstanceOf[Message.OnNext]
        target.onNext(m.elem)(m.from)
      case 5 ⇒
        val m = msg.asInstanceOf[Message.OnComplete]
        target.onComplete()(m.from)
      case 6 ⇒
        val m = msg.asInstanceOf[Message.OnError]
        target.onError(m.error)(m.from)
      case 7 ⇒
        target.xStart()
      case 8 ⇒
        target.xEvent(msg.asInstanceOf[Message.XEvent].ev)
    }
  }

  def scheduleEvent(target: Stage, delay: FiniteDuration, ev: AnyRef): Cancellable =
    scheduleXEvent(delay, new Message.XEvent(target, ev, this))

  def scheduleTimeout(target: Stage, delay: FiniteDuration): Cancellable = {
    val msg   = new Message.XEvent(target, null, this)
    val timer = scheduleXEvent(delay, msg)
    msg.ev = Timeout(timer)
    timer
  }

  private def scheduleXEvent(delay: FiniteDuration, msg: Message.XEvent): Cancellable =
    if (delay > Duration.Zero) {
      // we can run the event Runnable directly on the scheduler thread since
      // all it does is enqueueing the event on the runner's dispatcher
      scheduler.scheduleOnce(delay, msg)(CallingThreadExecutionContext)
    } else {
      msg.run()
      Cancellable.Inactive
    }

  override def toString = dispatcher.name + '@' + Integer.toHexString(System.identityHashCode(this))
}

private[core] object StreamRunner {

  final case class Timeout(timer: Cancellable)

  protected sealed abstract class Message(val id: Int, val target: Stage)
  protected object Message {
    final class Subscribe(target: Stage, val from: Outport)                    extends Message(0, target)
    final class Request(target: Stage, val n: Long, val from: Outport)         extends Message(1, target)
    final class Cancel(target: Stage, val from: Outport)                       extends Message(2, target)
    final class OnSubscribe(target: Stage, val from: Inport)                   extends Message(3, target)
    final class OnNext(target: Stage, val elem: AnyRef, val from: Inport)      extends Message(4, target)
    final class OnComplete(target: Stage, val from: Inport)                    extends Message(5, target)
    final class OnError(target: Stage, val error: Throwable, val from: Inport) extends Message(6, target)
    final class XStart(target: Stage)                                          extends Message(7, target)
    final class XEvent(target: Stage, @volatile private[StreamRunner] var ev: AnyRef, runner: StreamRunner)
        extends Message(8, target)
        with Runnable {
      def run() = runner.enqueue(this)
    }
  }

  private final class AssignToRegion(val runner: StreamRunner, isDefault: Boolean, ovrride: Boolean = false)
      extends (PipeElem ⇒ Boolean) {
    def _apply(pe: PipeElem): Boolean = apply(pe)
    @tailrec def apply(pe: PipeElem): Boolean =
      pe match {
        case x: AsyncBoundaryStage ⇒
          // async boundary stages belong to their upstream runner
          x.runner = x.inputElem.asInstanceOf[Stage].runner
          true
        case stage: Stage if (stage.runner eq null) || (ovrride && (stage.runner ne runner)) ⇒
          stage.runner = runner
          import pe._
          3 * inputElems.size012 + outputElems.size012 match {
            case 0 /* 0:0 */ ⇒ throw new IllegalStateException // no input and no output?
            case 1 /* 0:1 */ ⇒
              stage match {
                case x: SubSpoutStage ⇒ subStreamBoundary(x.in, x, outputElems.head)
                case _                ⇒ apply(outputElems.head)
              }
            case 2 /* 0:x */ ⇒ outputElems.forall(this)
            case 3 /* 1:0 */ ⇒
              stage match {
                case x: SubDrainStage ⇒ subStreamBoundary(x.out, x, inputElems.head)
                case _                ⇒ apply(inputElems.head)
              }
            case 4 /* 1:1 */ ⇒ _apply(inputElems.head) && apply(outputElems.head)
            case 5 /* 1:x */ ⇒ _apply(inputElems.head) && outputElems.forall(this)
            case 6 /* x:0 */ ⇒ inputElems.forall(this)
            case 7 /* x:1 */ ⇒ inputElems.forall(this) && apply(outputElems.head)
            case 8 /* x:x */ ⇒ inputElems.forall(this) && outputElems.forall(this)
          }
        case _ ⇒ true
      }

    def subStreamBoundary(parent: Stage, subStream: Stage, next: PipeElem): Boolean =
      if (parent.runner ne null) {
        if (parent.runner ne runner) {
          if (isDefault) {
            // if we have a default runner assignment for the sub-stream move it onto the same runner as the parent
            new AssignToRegion(parent.runner, isDefault = false, ovrride = true).apply(subStream.pipeElem)
            false // break out of the outer assignment loop
          } else
            throw new IllegalAsyncBoundaryException(
              "An asynchronous sub-stream with a non-default dispatcher assignment (in this case `" +
                runner.dispatcher.name + "`) must be fenced off from its parent stream with explicit async boundaries!")
        } else apply(next) // parent and sub-stream port are on the same runner (as required!)
      } else
        throw new IllegalAsyncBoundaryException(
          "A synchronous parent stream must not contain " +
            "an async sub-stream. You can fix this by explicitly marking the parent stream as `async`.")
  }

  def assignRunners(needRunner: StageDispatcherIdListMap)(implicit env: StreamEnv): Unit = {

    def createAssign(dispatcher: Dispatcher, isDefault: Boolean) =
      new AssignToRegion(new StreamRunner(env.settings.throughput, env.log, dispatcher, env.scheduler), isDefault)

    @tailrec def assignNonDefaults(remaining: StageDispatcherIdListMap, defaultAssignments: List[Stage]): List[Stage] =
      if (remaining.nonEmpty) {
        import remaining._
        if (dispatcherId.nonEmpty) {
          val assign = createAssign(env.dispatchers(dispatcherId), isDefault = false)
          if (stage.runner ne null) {
            throw new IllegalAsyncBoundaryException(
              "Conflicting dispatcher assignment to async region containing stage " +
                s"'${stage.getClass.getSimpleName}': [${assign.runner.dispatcher.name}] vs. [${stage.runner.dispatcher.name}]")
          } else assign(stage.asInstanceOf[PipeElem])
          assignNonDefaults(tail, defaultAssignments)
        } else assignNonDefaults(tail, stage :: defaultAssignments)
      } else defaultAssignments

    val defaultAssignments = assignNonDefaults(needRunner, Nil)
    defaultAssignments.foreach { stage ⇒
      if (stage.runner eq null) {
        val assign = createAssign(env.defaultDispatcher, isDefault = true)
        assign(stage.asInstanceOf[PipeElem])
      }
    }
  }

  final class StageDispatcherIdListMap(val stage: Stage, val dispatcherId: String, tail: StageDispatcherIdListMap)
      extends ImsiList[StageDispatcherIdListMap](tail)
  object StageDispatcherIdListMap {
    def empty: StageDispatcherIdListMap = null
    def apply(stage: Stage, dispatcherId: String, tail: StageDispatcherIdListMap) =
      new StageDispatcherIdListMap(stage, dispatcherId, tail)
  }

  final class StageStageList(val stage0: Stage, val stage1: Stage, tail: StageStageList)
      extends ImsiList[StageStageList](tail)
  object StageStageList {
    def empty: StageStageList = null
    def apply(stage0: Stage, stage1: Stage, tail: StageStageList) =
      new StageStageList(stage0, stage1, tail)
  }
}
