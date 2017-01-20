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
      case 0 ⇒ target.subscribe()(msg.asInstanceOf[Message.Subscribe].from)
      case 1 ⇒ target._subscribe(msg.asInstanceOf[Message.Subscribe].from)
      case 2 ⇒ { val m = msg.asInstanceOf[Message.Request]; target.request(m.n)(m.from) }
      case 3 ⇒ { val m = msg.asInstanceOf[Message.RequestInterception]; target._request(m.n, m.from) }
      case 4 ⇒ target.cancel()(msg.asInstanceOf[Message.Cancel].from)
      case 5 ⇒ target._cancel(msg.asInstanceOf[Message.Cancel].from)
      case 6 ⇒ target.onSubscribe()(msg.asInstanceOf[Message.OnSubscribe].from)
      case 7 ⇒ target._onSubscribe(msg.asInstanceOf[Message.OnSubscribe].from)
      case 8 ⇒ { val m = msg.asInstanceOf[Message.OnNext]; target.onNext(m.elem)(m.from) }
      case 9 ⇒ { val m = msg.asInstanceOf[Message.OnNext]; target._onNext(m.elem, m.from) }
      case 10 ⇒ target.onComplete()(msg.asInstanceOf[Message.OnComplete].from)
      case 11 ⇒ target._onComplete(msg.asInstanceOf[Message.OnComplete].from)
      case 12 ⇒ { val m = msg.asInstanceOf[Message.OnError]; target.onError(m.error)(m.from) }
      case 13 ⇒ { val m = msg.asInstanceOf[Message.OnError]; target._onError(m.error, m.from) }
      case 14 => target.xEvent(msg.asInstanceOf[Message.XEvent].ev)
      case 15 => target._xEvent(msg.asInstanceOf[Message.XEvent].ev)
      case 16 ⇒ target.xStart()
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
    private def id(i: Int, intercept: Boolean) = if (intercept) i + 1 else i

    final class Subscribe(target: StageImpl, val from: Outport, intercept: Boolean = false)
      extends Message(id(0, intercept), target)
    final class Request(target: StageImpl, val n: Long, val from: Outport) extends Message(2, target)
    final class RequestInterception(target: StageImpl, val n: Int, val from: Outport) extends Message(3, target)
    final class Cancel(target: StageImpl, val from: Outport, intercept: Boolean = false)
      extends Message(id(4, intercept), target)
    final class OnSubscribe(target: StageImpl, val from: Inport, intercept: Boolean = false)
      extends Message(id(6, intercept), target)
    final class OnNext(target: StageImpl, val elem: AnyRef, val from: Inport, intercept: Boolean = false)
      extends Message(id(8, intercept), target)
    final class OnComplete(target: StageImpl, val from: Inport, intercept: Boolean = false)
      extends Message(id(10, intercept), target)
    final class OnError(target: StageImpl, val error: Throwable, val from: Inport, intercept: Boolean = false)
      extends Message(id(12, intercept), target)
    final class XEvent(target: StageImpl, @volatile private[StreamRunner] var ev: AnyRef, runner: StreamRunner,
                       intercept: Boolean = false) extends Message(id(14, intercept), target)
      with Runnable {
      def run(): Unit = runner.enqueue(this)
    }
    final class XStart(target: StageImpl) extends Message(16, target)
  }

}
