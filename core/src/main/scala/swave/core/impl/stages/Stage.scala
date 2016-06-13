/*
 * Copyright © 2016 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swave.core.impl.stages

import scala.annotation.{ compileTimeOnly, tailrec }
import swave.core.PipeElem
import swave.core.util._
import swave.core.impl._

/**
 * Signals:
 * - subscribe
 * - request
 * - cancel
 * - onSubscribe
 * - onNext
 * - onComplete
 * - onError
 * and
 * - xSeal
 * - xStart
 * - xRun
 * - xCleanUp
 *
 * Phases (disjunct sets of states):
 *
 * CONNECTING (potentially several states)
 * - must throw exception upon reception of signal other than `subscribe` and `onSubscribe`
 * - upon signal reception:
 *   - must not allocate resources which require explicit releasing
 *   - must transition to AWAITING-XSEAL when all expected peers are wired
 *
 * AWAITING-XSEAL (single state)
 * - must throw exception upon reception of signal other than `xSeal`
 * - upon reception of `xSeal`:
 *   - must propagate `xSeal` signal to all connected peer stages
 *   - must not send non-`xSeal` signals
 *   - may register for reception of `xStart`, `xRun` and/or `xCleanUp`
 *   - when registered for reception of `xStart`:
 *     - must queue (intercept) all other signals until reception of `xStart`
 *     - must transition to AWAITING-XSTART
 *   - otherwise: must transition to RUNNING
 *
 * AWAITING-XSTART (single state)
 * - only for stages which have previously registered interest in `xStart`
 * - must throw exception upon reception of signal other than `xStart` or `xSeal`
 * - must ignore `xSeal`
 * - upon reception of `xStart`:
 *   - may send one or more non-x signals
 *   - must transition to RUNNING
 *   - must dequeue all intercepted signals
 *
 * RUNNING (potentially several states)
 * - must throw exception upon reception of `xStart`, `xCleanUp` or `xRun` (the latter is ok if interest was previously registered)
 * - must ignore `xSeal`
 * - upon signal reception:
 *   - may send one or more non-x signals
 *   - must queue (intercept) all signals while handling one signal instance
 *   - must dequeue all intercepted signals after having finished handling one signal instance
 *   - may (re-)register for `xRun` reception
 *   - may transition to AWAITING-XCLEANUP or STOPPED
 *
 * AWAITING-XCLEANUP (single state)
 * - only for stages which have previously registered interest in `xCleanUp`
 * - must ignore all signals other than `xCleanUp`
 * - upon reception of `xCleanUp`:
 *   - clean up internal state / release resources
 *   - transition to STOPPED
 *
 * STOPPED (single state)
 * - ignore all signals
 */

private[swave] abstract class Stage extends PipeElemImpl { this: PipeElem.Basic ⇒
  import Stage.InportStates

  type State = Int // semantic alias

  // TODO: evaluate moving the two booleans into the (upper) _state integer bits
  private[this] var _intercepting = false
  private[this] var _sealed = false
  private[this] var _state: State = _ // current state; the STOPPED state is always encoded as zero
  private[this] var _mbs: Int = _
  private[this] var _inportState: InportStates = _
  private[this] var _buffer: ResizableRingBuffer[AnyRef] = _

  /**
   * The [[StreamRunner]] that is assigned to the given stage.
   */
  private[core] final var runner: StreamRunner = _ // null -> sync run, non-null -> async run

  protected final var interceptingStates: Int = _ // bit mask holding a 1 bit for every state which requires interception support

  protected final def configureFrom(ctx: RunContext): Unit = _mbs = ctx.env.settings.maxBatchSize

  protected final implicit def self: this.type = this

  protected final def initialState(s: State): Unit = _state = s

  protected final def stay(): State = _state

  protected final def illegalState(msg: String) = throw new IllegalStateException(msg + " in " + this)

  protected final def setIntercepting(flag: Boolean): Unit = _intercepting = flag

  override def toString = s"${getClass.getSimpleName}@${identityHash(this)}/$stateName"

  def stateName: String = s"<unknown id ${_state}>"

  final def isSealed: Boolean = _sealed

  /////////////////////////////////////// SUBSCRIBE ///////////////////////////////////////

  final def subscribe()(implicit from: Outport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _subscribe(from)
      handleInterceptions()
    } else storeInterception(Statics._0L, from)

  private def _subscribe(from: Outport): Unit = _state = _subscribe0(from)

  protected def _subscribe0(from: Outport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ illegalState(s"Unexpected subscribe() from out '$from'")
    }

  /////////////////////////////////////// REQUEST ///////////////////////////////////////

  final def request(n: Long)(implicit from: Outport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _request(n, from)
      handleInterceptions()
    } else storeInterception(Statics._1L, if (n <= 16) Statics.LONGS(n.toInt - 1) else new java.lang.Long(n), from)

  private def _request(n: Long, from: Outport): Unit = {
    val count =
      if (n > _mbs) {
        from.asInstanceOf[Stage].updateInportState(this, n)
        _mbs
      } else n.toInt
    _state = _request0(count, from)
  }

  protected def _request0(n: Int, from: Outport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ illegalState(s"Unexpected request($n) from out '$from'")
    }

  /////////////////////////////////////// CANCEL ///////////////////////////////////////

  final def cancel()(implicit from: Outport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      from.asInstanceOf[Stage].clearInportState(this)
      _cancel(from)
      handleInterceptions()
    } else {
      from.asInstanceOf[Stage].clearInportState(this)
      storeInterception(Statics._2L, from)
    }

  private def _cancel(from: Outport): Unit = _state = _cancel0(from)

  protected def _cancel0(from: Outport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ illegalState(s"Unexpected cancel() from out '$from'")
    }

  /////////////////////////////////////// ONSUBSCRIBE ///////////////////////////////////////

  final def onSubscribe()(implicit from: Inport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _onSubscribe(from)
      handleInterceptions()
    } else storeInterception(Statics._3L, from)

  private def _onSubscribe(from: Inport): Unit = _state = _onSubscribe0(from)

  protected def _onSubscribe0(from: Inport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ illegalState(s"Unexpected onSubscribe() from out '$from'")
    }

  /////////////////////////////////////// ONNEXT ///////////////////////////////////////

  final def onNext(elem: AnyRef)(implicit from: Inport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _onNext(elem, from)
      handleInterceptions()
    } else storeInterception(Statics._4L, elem, from)

  @tailrec
  private def _onNext(elem: AnyRef, from: Inport, last: InportStates = null, current: InportStates = _inportState): Unit =
    if (current ne null) {
      if (current.in eq from) {
        _state = _onNext0(elem, from)
        if (current.tail ne current) { // is `current` still valid, i.e. not cancelled? (tail eq self is special condition)
          val newPending = current.pending - 1
          if (newPending == 0) {
            val newRemaining = current.remaining - _mbs
            if (newRemaining > 0) {
              current.pending = _mbs
              current.remaining = newRemaining
              from.request(_mbs.toLong)
            } else {
              if (last ne null) last.tail = current.tail else _inportState = current.tail
              from.request(current.remaining)
            }
          } else current.pending = newPending
        }
      } else _onNext(elem, from, current, current.tail)
    } else _state = _onNext0(elem, from)

  protected def _onNext0(elem: AnyRef, from: Inport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ illegalState(s"Unexpected onNext($elem) from out '$from'")
    }

  /////////////////////////////////////// ONCOMPLETE ///////////////////////////////////////

  final def onComplete()(implicit from: Inport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _onComplete(from)
      handleInterceptions()
    } else storeInterception(Statics._5L, from)

  private def _onComplete(from: Inport): Unit = {
    clearInportState(from)
    _state = _onComplete0(from)
  }

  protected def _onComplete0(from: Inport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ illegalState(s"Unexpected onComplete() from out '$from'")
    }

  /////////////////////////////////////// ONERROR ///////////////////////////////////////

  final def onError(error: Throwable)(implicit from: Inport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _onError(error, from)
      handleInterceptions()
    } else storeInterception(Statics._6L, error, from)

  private def _onError(error: Throwable, from: Inport): Unit = {
    clearInportState(from)
    _state = _onError0(error, from)
  }

  protected def _onError0(error: Throwable, from: Inport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ illegalState(s"Unexpected onError($error) from out '$from'")
    }

  /////////////////////////////////////// XSEAL ///////////////////////////////////////

  final def xSeal(ctx: RunContext): Unit =
    if (!_sealed) {
      _sealed = true
      _state = _xSeal(ctx)
    }

  protected def _xSeal(ctx: RunContext): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ illegalState(s"Unexpected xSeal(...)")
    }

  /////////////////////////////////////// XSTART ///////////////////////////////////////

  final def xStart(): Unit = {
    _state = _xStart()
    handleInterceptions()
  }

  protected def _xStart(): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ illegalState(s"Unexpected xStart()")
    }

  /////////////////////////////////////// XEVENT ///////////////////////////////////////

  final def xEvent(ev: AnyRef): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _xEvent(ev)
      handleInterceptions()
    } else storeInterception(Statics._7L, ev)

  private def _xEvent(ev: AnyRef): Unit = _state = _xEvent0(ev)

  protected def _xEvent0(ev: AnyRef): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ illegalState(s"Unexpected xEvent($ev)")
    }

  /////////////////////////////////////// STATE DESIGNATOR ///////////////////////////////////////

  @compileTimeOnly("`state(...) can only be used as the single implementation expression of a 'State Def'!")
  protected final def state(
    intercept: Boolean = true,
    subscribe: Outport ⇒ State = null,
    request: (Int, Outport) ⇒ State = null,
    cancel: Outport ⇒ State = null,
    onSubscribe: Inport ⇒ State = null,
    onNext: (AnyRef, Inport) ⇒ State = null,
    onComplete: Inport ⇒ State = null,
    onError: (Throwable, Inport) ⇒ State = null,
    xSeal: RunContext ⇒ State = null,
    xStart: () ⇒ State = null,
    xEvent: AnyRef ⇒ State = null): State = 0

  /////////////////////////////////////// STOPPERS ///////////////////////////////////////

  protected final def stop(): State = {
    _buffer = null // don't hang on to elements
    0 // STOPPED state encoding
  }

  protected final def stopF(out: Outport): State = stop()

  protected final def stopCancel(in: Inport): State = {
    in.cancel()
    stop()
  }

  protected final def stopCancelF(in: Inport)(out: Outport): State = stopCancel(in)

  protected final def stopCancel[L >: Null <: AbstractInportList[L]](ins: L, except: Inport = null): State = {
    cancelAll(ins, except)
    stop()
  }

  protected final def stopCancelF[L >: Null <: AbstractInportList[L]](ins: L)(out: Outport): State = stopCancel(ins)

  protected final def stopComplete(out: Outport): State = {
    out.onComplete()
    stop()
  }

  protected final def stopCompleteF(out: Outport)(in: Inport): State = stopComplete(out)

  protected final def stopError(e: Throwable, out: Outport): State = {
    out.onError(e)
    stop()
  }

  protected final def stopErrorF(out: Outport)(e: Throwable, in: Inport): State =
    stopError(e, out)

  /////////////////////////////////////// CANCEL HELPERS ///////////////////////////////////////

  @tailrec protected final def cancelAll[L >: Null <: AbstractInportList[L]](ins: L, except: Inport = null): Unit =
    if (ins ne null) {
      if (ins.in ne except) ins.in.cancel()
      cancelAll(ins.tail)
    }

  protected final def cancelAllAndStopComplete[L >: Null <: AbstractInportList[L]](ins: L, except: Inport, out: Outport): State = {
    cancelAll(ins, except)
    stopComplete(out)
  }

  protected final def cancelAllAndStopCompleteF[L >: Null <: AbstractInportList[L]](ins: L, out: Outport)(in: Inport): State = {
    cancelAll(ins, except = in)
    stopComplete(out)
  }

  protected final def cancelAllAndStopErrorF[L >: Null <: AbstractInportList[L]](ins: L, out: Outport)(e: Throwable, in: Inport): State = {
    cancelAll(ins, except = in)
    stopError(e, out)
  }

  /////////////////////////////////////// PRIVATE ///////////////////////////////////////

  private def interceptionNeeded: Boolean = ((interceptingStates >> _state) & 1) != 0

  private def storeInterception(signal: java.lang.Long, arg: AnyRef): Unit =
    if (!buffer.write(signal, arg))
      illegalState(s"Interception buffer overflow on signal $signal($arg)'")

  private def storeInterception(signal: java.lang.Long, arg0: AnyRef, arg1: AnyRef): Unit =
    if (!buffer.write(signal, arg0, arg1))
      illegalState(s"Interception buffer overflow on signal $signal($arg0, $arg1)")

  private def buffer = {
    if (_buffer eq null) {
      val initialSize = roundUpToNextPowerOf2(_mbs)
      _buffer = new ResizableRingBuffer(initialSize, initialSize << 4)
    }
    _buffer
  }

  @tailrec private def handleInterceptions(): Unit =
    if ((_buffer ne null) && _buffer.nonEmpty) {
      val signal = _buffer.unsafeRead().asInstanceOf[java.lang.Long].intValue()
      val arg0 = _buffer.unsafeRead()
      signal match {
        case 0 ⇒ _subscribe(arg0.asInstanceOf[Stage])
        case 1 ⇒ _request(arg0.asInstanceOf[java.lang.Long].longValue(), _buffer.unsafeRead().asInstanceOf[Stage])
        case 2 ⇒ _cancel(arg0.asInstanceOf[Stage])
        case 3 ⇒ _onSubscribe(arg0.asInstanceOf[Stage])
        case 4 ⇒ _onNext(arg0, _buffer.unsafeRead().asInstanceOf[Stage])
        case 5 ⇒ _onComplete(arg0.asInstanceOf[Stage])
        case 6 ⇒ _onError(arg0.asInstanceOf[Throwable], _buffer.unsafeRead().asInstanceOf[Stage])
        case 7 ⇒ _xEvent(arg0)
      }
      handleInterceptions()
    } else _intercepting = false

  private def updateInportState(in: Inport, requested: Long): Unit = {
    requireArg(requested > _mbs)
    @tailrec def rec(current: InportStates): Unit =
      if (current ne null) {
        if (current.in eq in) current.remaining = current.remaining ⊹ requested
        else rec(current.tail)
      } else _inportState = new InportStates(in, _inportState, _mbs, requested - _mbs)
    rec(_inportState)
  }

  private def clearInportState(in: Inport): Unit =
    _inportState = _inportState.remove(in)
}

private[swave] object Stage {

  private class InportStates(in: Inport, tail: InportStates,
    var pending: Int, // already requested from `in` but not yet received
    var remaining: Long) // already requested by us but not yet forwarded to `in`
      extends AbstractInportList[InportStates](in, tail)

  private[stages] class OutportStates(out: Outport, tail: OutportStates,
    var remaining: Long) // requested by this `out` but not yet delivered, i.e. unfulfilled demand
      extends AbstractOutportList[OutportStates](out, tail)
}