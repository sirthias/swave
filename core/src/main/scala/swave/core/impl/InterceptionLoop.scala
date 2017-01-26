/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import scala.annotation.switch
import swave.core.impl.stages.StageImpl
import swave.core.impl.util.ResizableRingBuffer

private[impl] final class InterceptionLoop(initialBufferSize: Int) {

  private[this] val signalBuffer: ResizableRingBuffer[AnyRef] =
    new ResizableRingBuffer(initialBufferSize, initialBufferSize << 4)

  def enqueueSubscribe(target: StageImpl, from: Outport): Unit =
    if (from eq null) store(target, Statics._0) else store(target, Statics._1, from)
  def enqueueRequest(target: StageImpl, n: Int, from: Outport): Unit =
    if (from eq null) store(target, if (n <= 32) Statics.NEG_INTS(n - 1) else new Integer(-n))
    else store(target, if (n <= 32) Statics.INTS_PLUS_100(n - 1) else new Integer(n + 100), from)
  def enqueueCancel(target: StageImpl, from: Outport): Unit =
    if (from eq null) store(target, Statics._2) else store(target, Statics._3, from)
  def enqueueOnSubscribe(target: StageImpl, from: Inport): Unit =
    if (from eq null) store(target, Statics._4) else store(target, Statics._5, from)
  def enqueueOnNext(target: StageImpl, elem: AnyRef, from: Inport): Unit =
    if (from eq null) store(target, Statics._6, elem) else store(target, Statics._7, elem, from)
  def enqueueOnComplete(target: StageImpl, from: Inport): Unit =
    if (from eq null) store(target, Statics._8) else store(target, Statics._9, from)
  def enqueueOnError(target: StageImpl, e: Throwable, from: Inport): Unit =
    if (from eq null) store(target, Statics._10, e) else store(target, Statics._11, e, from)
  def enqueueXEvent(target: StageImpl, ev: AnyRef): Unit =
    store(target, Statics._12, ev)

  private def store(a: AnyRef, b: AnyRef): Unit =
    if (!signalBuffer.write(a, b)) throwBufOverflow()
  private def store(a: AnyRef, b: AnyRef, c: AnyRef): Unit =
    if (!signalBuffer.write(a, b, c)) throwBufOverflow()
  private def store(a: AnyRef, b: AnyRef, c: AnyRef, d: AnyRef): Unit =
    if (!signalBuffer.write(a, b, c, d)) throwBufOverflow()

  private def throwBufOverflow() = throw new IllegalStateException("Interception signal buffer overflow")

  def hasInterception: Boolean = signalBuffer.nonEmpty

  def handleInterception(): Unit = {
    def read()       = signalBuffer.unsafeRead()
    def readNoZero() = signalBuffer.unsafeRead_NoZero()
    def readInt()    = readNoZero().asInstanceOf[java.lang.Integer].intValue()
    def readStage()  = readNoZero().asInstanceOf[StageImpl]
    val target       = readStage()
    (readInt(): @switch) match {
      case 0  ⇒ target._subscribe(null)
      case 1  ⇒ target._subscribe(readStage())
      case 2  ⇒ target._cancel(null)
      case 3  ⇒ target._cancel(readStage())
      case 4  ⇒ target._onSubscribe(null)
      case 5  ⇒ target._onSubscribe(readStage())
      case 6  ⇒ target._onNext(read(), null)
      case 7  ⇒ target._onNext(read(), readStage())
      case 8  ⇒ target._onComplete(null)
      case 9  ⇒ target._onComplete(readStage())
      case 10 ⇒ target._onError(read().asInstanceOf[Throwable], null)
      case 11 ⇒ target._onError(read().asInstanceOf[Throwable], readStage())
      case 12 ⇒ target._xEvent(read())
      case x =>
        if (x < 0) target._request(-x, null)
        else target._request(x - 100, readStage())
    }
  }
}
