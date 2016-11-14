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
import swave.core.impl.stages.StageImpl
import swave.core.impl.util.ImsiList
import swave.core.util._
import swave.core._

private[core] final class StreamRunner(_throughput: Int, _log: Logger, _dispatcher: Dispatcher, scheduler: Scheduler)
    extends StreamActor(_throughput, _log, _dispatcher) {
  import StreamRunner._

  protected type MessageType = Message

  startMessageProcessing()

  def enqueueRequest(target: StageImpl, n: Long)(implicit from: Outport): Unit =
    enqueue(new StreamRunner.Message.Request(target, n, from))
  def enqueueCancel(target: StageImpl)(implicit from: Outport): Unit =
    enqueue(new StreamRunner.Message.Cancel(target, from))

  def enqueueOnNext(target: StageImpl, elem: AnyRef)(implicit from: Inport): Unit =
    enqueue(new StreamRunner.Message.OnNext(target, elem, from))
  def enqueueOnComplete(target: StageImpl)(implicit from: Inport): Unit =
    enqueue(new StreamRunner.Message.OnComplete(target, from))
  def enqueueOnError(target: StageImpl, e: Throwable)(implicit from: Inport): Unit =
    enqueue(new StreamRunner.Message.OnError(target, e, from))

  def enqueueXStart(target: StageImpl): Unit =
    enqueue(new StreamRunner.Message.XStart(target))
  def enqueueXEvent(target: StageImpl, ev: AnyRef): Unit =
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

  def scheduleEvent(target: StageImpl, delay: FiniteDuration, ev: AnyRef): Cancellable =
    scheduleXEvent(delay, new Message.XEvent(target, ev, this))

  def scheduleTimeout(target: StageImpl, delay: FiniteDuration): Cancellable = {
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

  protected sealed abstract class Message(val id: Int, val target: StageImpl)
  protected object Message {
    final class Subscribe(target: StageImpl, val from: Outport)                    extends Message(0, target)
    final class Request(target: StageImpl, val n: Long, val from: Outport)         extends Message(1, target)
    final class Cancel(target: StageImpl, val from: Outport)                       extends Message(2, target)
    final class OnSubscribe(target: StageImpl, val from: Inport)                   extends Message(3, target)
    final class OnNext(target: StageImpl, val elem: AnyRef, val from: Inport)      extends Message(4, target)
    final class OnComplete(target: StageImpl, val from: Inport)                    extends Message(5, target)
    final class OnError(target: StageImpl, val error: Throwable, val from: Inport) extends Message(6, target)
    final class XStart(target: StageImpl)                                          extends Message(7, target)
    final class XEvent(target: StageImpl, @volatile private[StreamRunner] var ev: AnyRef, runner: StreamRunner)
        extends Message(8, target)
        with Runnable {
      def run() = runner.enqueue(this)
    }
  }

  private final class AssignToRegion(val runner: StreamRunner, isDefault: Boolean, ovrride: Boolean = false)
      extends (Stage ⇒ Boolean) {
    def _apply(stage: Stage): Boolean = apply(stage)
    @tailrec def apply(stage: Stage): Boolean =
      stage match {
        case x: AsyncBoundaryStage ⇒
          // async boundary stages belong to their upstream runner
          x.runner = x.inputStages.head.stageImpl.runner
          true
        case s: StageImpl if (s.runner eq null) || (ovrride && (s.runner ne runner)) ⇒
          s.runner = runner
          3 * s.inputStages.size012 + s.outputStages.size012 match {
            case 0 /* 0:0 */ ⇒ throw new IllegalStateException // no input and no output?
            case 1 /* 0:1 */ ⇒
              s match {
                case x: SubSpoutStage ⇒ subStreamBoundary(x.in, x, s.outputStages.head)
                case _                ⇒ apply(s.outputStages.head)
              }
            case 2 /* 0:x */ ⇒ s.outputStages.forall(this)
            case 3 /* 1:0 */ ⇒
              s match {
                case x: SubDrainStage ⇒ subStreamBoundary(x.out, x, s.inputStages.head)
                case _                ⇒ apply(s.inputStages.head)
              }
            case 4 /* 1:1 */ ⇒ _apply(s.inputStages.head) && apply(s.outputStages.head)
            case 5 /* 1:x */ ⇒ _apply(s.inputStages.head) && s.outputStages.forall(this)
            case 6 /* x:0 */ ⇒ s.inputStages.forall(this)
            case 7 /* x:1 */ ⇒ s.inputStages.forall(this) && apply(s.outputStages.head)
            case 8 /* x:x */ ⇒ s.inputStages.forall(this) && s.outputStages.forall(this)
          }
        case _ ⇒ true
      }

    def subStreamBoundary(parent: StageImpl, subStream: StageImpl, next: Stage): Boolean =
      if (parent.runner ne null) {
        if (parent.runner ne runner) {
          if (isDefault) {
            // if we have a default runner assignment for the sub-stream move it onto the same runner as the parent
            new AssignToRegion(parent.runner, isDefault = false, ovrride = true).apply(subStream)
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

    @tailrec
    def assignNonDefaults(remaining: StageDispatcherIdListMap, defaultAssignments: List[StageImpl]): List[StageImpl] =
      if (remaining.nonEmpty) {
        import remaining._
        if (dispatcherId.nonEmpty) {
          val assign = createAssign(env.dispatchers(dispatcherId), isDefault = false)
          if (stage.runner ne null) {
            throw new IllegalAsyncBoundaryException(
              "Conflicting dispatcher assignment to async region containing stage " +
                s"'${stage.getClass.getSimpleName}': [${assign.runner.dispatcher.name}] vs. [${stage.runner.dispatcher.name}]")
          } else assign(stage)
          assignNonDefaults(tail, defaultAssignments)
        } else assignNonDefaults(tail, stage :: defaultAssignments)
      } else defaultAssignments

    val defaultAssignments = assignNonDefaults(needRunner, Nil)
    defaultAssignments.foreach { stage ⇒
      if (stage.runner eq null) {
        val assign = createAssign(env.defaultDispatcher, isDefault = true)
        assign(stage)
      }
    }
  }

  final class StageDispatcherIdListMap(val stage: StageImpl, val dispatcherId: String, tail: StageDispatcherIdListMap)
      extends ImsiList[StageDispatcherIdListMap](tail)
  object StageDispatcherIdListMap {
    def empty: StageDispatcherIdListMap = null
    def apply(stage: StageImpl, dispatcherId: String, tail: StageDispatcherIdListMap) =
      new StageDispatcherIdListMap(stage, dispatcherId, tail)
  }

  final class StageStageList(val stage0: StageImpl, val stage1: StageImpl, tail: StageStageList)
      extends ImsiList[StageStageList](tail)
  object StageStageList {
    def empty: StageStageList = null
    def apply(stage0: StageImpl, stage1: StageImpl, tail: StageStageList) =
      new StageStageList(stage0, stage1, tail)
  }
}
