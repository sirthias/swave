/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages

import scala.annotation.tailrec
import swave.core.impl.{ Inport, Outport, RunContext }
import swave.core.util._

trait SignalStashing { this: Stage ⇒

  private[this] var _stash: ResizableRingBuffer[AnyRef] = _

  protected final def configureStash(ctx: RunContext): Unit = {
    val mbs = roundUpToNextPowerOf2(ctx.env.settings.maxBatchSize)
    _stash = new ResizableRingBuffer(mbs, mbs << 4)
  }

  protected final def stashSubscribe(from: Outport): State = {
    stashSignal(Statics._0L, from)
    stay()
  }

  protected final def stashRequest(n: Int, from: Outport): State = {
    stashSignal(Statics._1L, if (n <= 16) Statics.LONGS(n - 1) else new java.lang.Long(n.toLong), from)
    stay()
  }

  protected final def stashCancel(from: Outport): State = {
    stashSignal(Statics._2L, from)
    stay()
  }

  protected final def stashOnsubscribe(from: Inport): State = {
    stashSignal(Statics._3L, from)
    stay()
  }

  protected final def stashOnNext(elem: AnyRef, from: Inport): State = {
    stashSignal(Statics._4L, elem, from)
    stay()
  }

  protected final def stashOnComplete(from: Inport): State = {
    stashSignal(Statics._5L, from)
    stay()
  }

  protected final def stashOnError(e: Throwable, from: Inport): State = {
    stashSignal(Statics._6L, e, from)
    stay()
  }

  protected final def stashXEvent(ev: AnyRef): State = {
    stashSignal(Statics._7L, ev)
    stay()
  }

  private def stashSignal(signal: java.lang.Long, arg: AnyRef): Unit =
    if (!_stash.write(signal, arg))
      illegalState(s"Stash overflow on signal $signal($arg)'")

  private def stashSignal(signal: java.lang.Long, arg0: AnyRef, arg1: AnyRef): Unit =
    if (!_stash.write(signal, arg0, arg1))
      illegalState(s"Stash overflow on signal $signal($arg0, $arg1)")

  @tailrec protected final def unstashAll(): Unit =
    if (_stash.nonEmpty) {
      val signal = _stash.unsafeRead().asInstanceOf[java.lang.Long].intValue()
      val arg0 = _stash.unsafeRead()
      signal match {
        case 0 ⇒ subscribe()(arg0.asInstanceOf[Stage])
        case 1 ⇒ request(arg0.asInstanceOf[java.lang.Long].longValue())(_stash.unsafeRead().asInstanceOf[Stage])
        case 2 ⇒ cancel()(arg0.asInstanceOf[Stage])
        case 3 ⇒ onSubscribe()(arg0.asInstanceOf[Stage])
        case 4 ⇒ onNext(arg0)(_stash.unsafeRead().asInstanceOf[Stage])
        case 5 ⇒ onComplete()(arg0.asInstanceOf[Stage])
        case 6 ⇒ onError(arg0.asInstanceOf[Throwable])(_stash.unsafeRead().asInstanceOf[Stage])
        case 7 ⇒ xEvent(arg0)
      }
      unstashAll()
    }

}
