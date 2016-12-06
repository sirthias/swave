/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import scala.annotation.{switch, tailrec}
import scala.concurrent.duration._
import com.typesafe.scalalogging.Logger
import swave.core.impl.stages.inout.AsyncBoundaryStage
import swave.core.impl.stages.StageImpl
import swave.core._

/**
  * A `StreamRunner` instance represents the execution environment for exactly one async region.
  * All signals destined for stages within the runners region go through the `enqueueXXX` methods of the runner.
  */
private[core] final class StreamRunner private (_disp: Dispatcher, ctx: RunSupport.RunContext)
    extends StreamActor(_disp, ctx.env.settings.throughput) {
  import StreamRunner._

  protected type MessageType = Message

  protected def log: Logger = ctx.env.log

  private[this] var _activeStagesCount = 0

  startMessageProcessing()

  private[impl] def registerStageStart(stage: StageImpl): Unit =
    _activeStagesCount += 1

  private[impl] def registerStageStop(stage: StageImpl): Unit = {
    _activeStagesCount -= 1
    if (_activeStagesCount == 0) ctx.unregisterRunner(this)
  }

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
    (msg.id: @switch) match {
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
      ctx.env.scheduler.scheduleOnce(delay, msg)(CallingThreadExecutionContext)
    } else {
      msg.run()
      Cancellable.Inactive
    }

  override def toString: String = dispatcher.name + '@' + Integer.toHexString(System.identityHashCode(this))
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
      def run(): Unit = runner.enqueue(this)
    }
  }

  /**
    * Applies all the given runner assignments to their respective async regions.
    * In case any invalid (i.e. conflicting) assignments are detected an [[IllegalAsyncBoundaryException]]
    * is thrown.
    *
    * Returns the list of new [[StreamRunner]] instances applied to the graph.
    */
  def assignRunners(assignments: List[Assignment], ctx: RunSupport.RunContext): List[StreamRunner] = {

    def applyRunner(stage: StageImpl, runner: StreamRunner): Unit =
      GraphTraverser.process(stage) {
        new GraphTraverser.Context {
          override def apply(stage: Stage): Boolean =
            (stage.stageImpl.runner eq null) && {
              stage match {
                case x: AsyncBoundaryStage => // async boundary stages belong to their upstream runner
                  x.runner = stage.inputStages.head.stageImpl.runner
                  false
                case _ =>
                  stage.stageImpl.runner = runner
                  true
              }
            }
        }
      }

    def assignRunner(stage: StageImpl, runner: StreamRunner): Unit =
      stage.runner match {
        case null     => applyRunner(stage, runner)
        case `runner` => // ok
        case x =>
          throw new IllegalAsyncBoundaryException(
            "An asynchronous sub-stream with a non-default dispatcher assignment (in this case `" +
              x.dispatcher.name + "`) must be fenced off from its parent stream with explicit async boundaries!")
      }

    def assignDispatcher(stage: StageImpl, dispatcher: Dispatcher): StreamRunner =
      stage.runner match {
        case null =>
          val runner = new StreamRunner(dispatcher, ctx)
          applyRunner(stage, runner)
          runner
        case x if x.dispatcher eq dispatcher => null
        case x =>
          throw new IllegalAsyncBoundaryException(
            "Conflicting dispatcher assignment to async region containing stage " +
              s"'${stage.getClass.getSimpleName}': [${dispatcher.name}] vs. [${x.dispatcher.name}]")
      }

    @tailrec
    def assignDefaults(remaining: List[StageImpl], runners: List[StreamRunner]): List[StreamRunner] =
      remaining match {
        case Nil => runners
        case stage :: tail =>
          val newRunners =
            if (stage.runner eq null) {
              val runner = new StreamRunner(ctx.env.defaultDispatcher, ctx)
              applyRunner(stage, runner)
              runner :: runners
            } else runners
          assignDefaults(tail, newRunners)
      }

    @tailrec
    def assignNonDefaults(remaining: List[Assignment],
                          defaultAssignments: List[StageImpl],
                          runners: List[StreamRunner]): List[StreamRunner] =
      remaining match {
        case Nil => assignDefaults(defaultAssignments, runners)
        case Assignment.Default(stage) :: tail =>
          assignNonDefaults(tail, stage :: defaultAssignments, runners)
        case Assignment.DispatcherId(stage, dispatcherId) :: tail =>
          val runner = assignDispatcher(stage, ctx.env.dispatchers(dispatcherId))
          assignNonDefaults(tail, defaultAssignments, if (runner ne null) runner :: runners else runners)
        case Assignment.Runner(stage, runner) :: tail =>
          assignRunner(stage, runner)
          assignNonDefaults(tail, defaultAssignments, runners)
      }

    assignNonDefaults(assignments, Nil, Nil)
  }

  sealed abstract class Assignment {
    def dispatcherId: String
  }
  object Assignment {
    def apply(stage: StageImpl, dispatcherId: String): Assignment =
      if (dispatcherId.isEmpty) Default(stage) else DispatcherId(stage, dispatcherId)

    final case class Default(stage: StageImpl) extends Assignment {
      def dispatcherId = "default"
    }
    final case class DispatcherId(stage: StageImpl, dispatcherId: String) extends Assignment
    final case class Runner(stage: StageImpl, runner: StreamRunner) extends Assignment {
      def dispatcherId: String = runner.dispatcher.name
    }
  }
}
