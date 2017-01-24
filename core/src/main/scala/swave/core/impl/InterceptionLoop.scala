/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import scala.annotation.switch
import swave.core.impl.stages.StageImpl
import swave.core.impl.util.{ResizableIntRingBuffer, ResizableRingBuffer}

private[impl] final class InterceptionLoop(initialBufferSize: Int) {

  private[this] var stageLookup: Array[StageImpl] = new Array[StageImpl](16)
  private[this] var stageLookupSize               = 0
  private[this] val intBuffer: ResizableIntRingBuffer =
    new ResizableIntRingBuffer(initialBufferSize, initialBufferSize << 4)
  private[this] val objBuffer: ResizableRingBuffer[AnyRef] =
    new ResizableRingBuffer(initialBufferSize, initialBufferSize << 2)

  def enqueueSubscribe(target: StageImpl, from: Outport): Unit =
    if (from eq null) store(0, target) else store(1, target, from)
  def enqueueRequest(target: StageImpl, n: Int, from: Outport): Unit =
    if (from eq null) store(2, target, n) else store(3, target, n, from)
  def enqueueCancel(target: StageImpl, from: Outport): Unit =
    if (from eq null) store(4, target) else store(5, target, from)
  def enqueueOnSubscribe(target: StageImpl, from: Inport): Unit =
    if (from eq null) store(6, target) else store(7, target, from)
  def enqueueOnNext(target: StageImpl, elem: AnyRef, from: Inport): Unit =
    if (from eq null) store(8, target, elem) else store(9, target, elem, from)
  def enqueueOnComplete(target: StageImpl, from: Inport): Unit =
    if (from eq null) store(10, target) else store(11, target, from)
  def enqueueOnError(target: StageImpl, e: Throwable, from: Inport): Unit =
    if (from eq null) store(12, target, e) else store(13, target, e, from)
  def enqueueXEvent(target: StageImpl, ev: AnyRef): Unit =
    store(14, target, ev)

  private def store(signal: Int, target: StageImpl): Unit =
    if (!intBuffer.write(signal, ix(target))) throwBufOverflow()
  private def store(signal: Int, target: StageImpl, n: Int): Unit =
    if (!intBuffer.write(signal, ix(target), n)) throwBufOverflow()
  private def store(signal: Int, target: StageImpl, n: Int, from: Port): Unit =
    if (!intBuffer.write(signal, ix(target), n, ix(from.stageImpl))) throwBufOverflow()
  private def store(signal: Int, target: StageImpl, from: Port): Unit =
    if (!intBuffer.write(signal, ix(target), ix(from.stageImpl))) throwBufOverflow()
  private def store(signal: Int, target: StageImpl, arg: AnyRef): Unit = {
    if (!intBuffer.write(signal, ix(target))) throwBufOverflow()
    if (!objBuffer.write(arg)) throwBufOverflow()
  }
  private def store(signal: Int, target: StageImpl, arg: AnyRef, from: Port): Unit = {
    if (!intBuffer.write(signal, ix(target), ix(from.stageImpl))) throwBufOverflow()
    if (!objBuffer.write(arg)) throwBufOverflow()
  }

  private def ix(stage: StageImpl): Int =
    if (stage.interceptionHelperIndex < 0) {
      val size = stageLookupSize
      if (size == stageLookup.length) stageLookup = java.util.Arrays.copyOf(stageLookup, size << 1)
      stageLookup(size) = stage
      stageLookupSize = size + 1
      stage.interceptionHelperIndex = size
      size
    } else stage.interceptionHelperIndex

  private def throwBufOverflow() = throw new IllegalStateException(s"Interception buffer overflow")

  //    private def logSignal(target: StageImpl, s: java.lang.Integer, args: AnyRef*): Unit = {
  //      RunContext.tempCount += 1
  //      val name = s.intValue() match {
  //        case 0 | 1 ⇒ "SUBSCRIBE"
  //        case 2 | 3 ⇒ "REQUEST"
  //        case 4 | 5 ⇒ "CANCEL"
  //        case 6 | 7 ⇒ "ONSUBSCRIBE"
  //        case 8 | 9 ⇒ "ONNEXT"
  //        case 10 | 11 ⇒ "ONCOMPLETE"
  //        case 12 | 13 ⇒ "ONERROR"
  //        case 14 ⇒ "XEVENT"
  //      }
  //      println(s"---${RunContext.tempCount}: $name(${args.mkString(", ")}) for $target")
  //    }

  def hasInterception: Boolean = intBuffer.nonEmpty

  def handleInterception(): Unit = {
    def readObj()   = objBuffer.unsafeRead()
    def readInt()   = intBuffer.unsafeRead()
    def readStage() = stageLookup(readInt())
    (readInt(): @switch) match {
      case 0  ⇒ readStage()._subscribe(null)
      case 1  ⇒ readStage()._subscribe(readStage())
      case 2  ⇒ readStage()._request(readInt(), null)
      case 3  ⇒ readStage()._request(readInt(), readStage())
      case 4  ⇒ readStage()._cancel(null)
      case 5  ⇒ readStage()._cancel(readStage())
      case 6  ⇒ readStage()._onSubscribe(null)
      case 7  ⇒ readStage()._onSubscribe(readStage())
      case 8  ⇒ readStage()._onNext(readObj(), null)
      case 9  ⇒ readStage()._onNext(readObj(), readStage())
      case 10 ⇒ readStage()._onComplete(null)
      case 11 ⇒ readStage()._onComplete(readStage())
      case 12 ⇒ readStage()._onError(readObj().asInstanceOf[Throwable], null)
      case 13 ⇒ readStage()._onError(readObj().asInstanceOf[Throwable], readStage())
      case 14 ⇒ readStage()._xEvent(readObj())
    }
  }
}
