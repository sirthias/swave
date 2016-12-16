/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import scala.annotation.switch
import scala.concurrent.duration._
import com.typesafe.scalalogging.Logger
import swave.core.impl.stages.StageImpl
import swave.core._

/**
  * A `StreamRunner` instance represents the execution environment for exactly one async region.
  * All signals destined for stages within the runners region go through the `enqueueXXX` methods of the runner.
  */
private[core] final class StreamRunner(_disp: Dispatcher, env: StreamEnv)
    extends StreamActor(_disp, env.settings.throughput)
    with Stage.Runner {
  import StreamRunner._

  protected type MessageType = Message

  val assignedDispatcherId: String =
    dispatcher.name match {
      case "default" => ""
      case x         => x
    }

  protected def log: Logger = env.log

  startMessageProcessing()

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

  private[impl] sealed abstract class Message(val id: Int, val target: StageImpl)
  private[impl] object Message {
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

}
