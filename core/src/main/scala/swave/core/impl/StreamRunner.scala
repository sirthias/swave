/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import scala.annotation.{switch, tailrec}
import scala.concurrent.duration._
import com.typesafe.scalalogging.Logger
import swave.core.impl.stages.StageImpl
import swave.core._

/**
  * A `StreamRunner` instance represents the execution environment for exactly one async region.
  * All signals destined for stages within the runners region go through the `enqueueXXX` methods of the runner.
  */
private[core] final class StreamRunner(_disp: Dispatcher, env: StreamEnv)
    extends StreamActor(_disp, env.settings.throughput) with Stage.Runner {
  import StreamRunner._

  protected type MessageType = Message

  val assignedDispatcherId: String =
    dispatcher.name match {
      case "default" => ""
      case x         => x
    }

  val interceptionLoop = new InterceptionLoop(env.settings.maxBatchSize)

  protected def log: Logger = env.log

  startMessageProcessing()

  protected def receive(msg: Message): Unit = {
    (msg.id: @switch) match {
      case 0 ⇒ { val m = msg.asInstanceOf[Message.Subscribe]; m.target.subscribe()(m.from) }
      case 1 ⇒ { val m = msg.asInstanceOf[Message.Request]; m.target.request(m.n)(m.from) }
      case 2 ⇒ { val m = msg.asInstanceOf[Message.Cancel]; m.target.cancel()(m.from) }
      case 3 ⇒ { val m = msg.asInstanceOf[Message.OnSubscribe]; m.target.onSubscribe()(m.from) }
      case 4 ⇒ { val m = msg.asInstanceOf[Message.OnNext]; m.target.onNext(m.elem)(m.from) }
      case 5 ⇒ { val m = msg.asInstanceOf[Message.OnComplete]; m.target.onComplete()(m.from) }
      case 6 ⇒ { val m = msg.asInstanceOf[Message.OnError]; m.target.onError(m.error)(m.from) }
      case 7 => { val m = msg.asInstanceOf[Message.XEvent]; m.target.xEvent(m.ev) }
      case 8 ⇒ startStages(msg.asInstanceOf[Message.Start].needXStart)
    }
    @tailrec def loop(): Unit =
      if (interceptionLoop.hasInterception) {
        interceptionLoop.handleInterception()
        if (mailboxEmpty) loop() // don't loop here if there are external messages that we need to handle as well
      }
    loop()
  }

  @tailrec
  private def startStages(remaining: List[StageImpl]): Unit =
    if (remaining ne Nil) {
      remaining.head.xStart()
      startStages(remaining.tail)
    }

  def scheduleEvent(target: StageImpl, delay: FiniteDuration, ev: AnyRef): Cancellable =
    scheduleXEvent(delay, new Message.XEvent(target, ev, this))

  def scheduleTimeout(target: StageImpl, delay: FiniteDuration): Cancellable = {
    val msg   = new Message.XEvent(target, null, this)
    val timer = scheduleXEvent(delay, msg)
    msg.ev = RunContext.Timeout(timer)
    timer
  }

  private def scheduleXEvent(delay: FiniteDuration, msg: Message.XEvent): Cancellable =
    if (delay > Duration.Zero) {
      // we can run the event Runnable directly on the scheduler thread since
      // all it does is enqueueing the event on the runner's dispatcher
      env.scheduler.scheduleOnce(delay, msg)(CallingThreadExecutionContext)
    } else {
      msg.run()
      Cancellable.Inactive
    }

  override def toString: String = dispatcher.name + '@' + Integer.toHexString(System.identityHashCode(this))
}

private[impl] object StreamRunner {

  private[impl] sealed abstract class Message(val id: Int)
  private[impl] object Message {
    final class Subscribe(val target: StageImpl, val from: Outport)                    extends Message(0)
    final class Request(val target: StageImpl, val n: Long, val from: Outport)         extends Message(1)
    final class Cancel(val target: StageImpl, val from: Outport)                       extends Message(2)
    final class OnSubscribe(val target: StageImpl, val from: Inport)                   extends Message(3)
    final class OnNext(val target: StageImpl, val elem: AnyRef, val from: Inport)      extends Message(4)
    final class OnComplete(val target: StageImpl, val from: Inport)                    extends Message(5)
    final class OnError(val target: StageImpl, val error: Throwable, val from: Inport) extends Message(6)
    final class XEvent(val target: StageImpl, @volatile private[StreamRunner] var ev: AnyRef, runner: StreamRunner)
        extends Message(7) with Runnable {
      def run(): Unit = runner.enqueue(this)
    }
    final class Start(val needXStart: List[StageImpl]) extends Message(8)
  }

}
