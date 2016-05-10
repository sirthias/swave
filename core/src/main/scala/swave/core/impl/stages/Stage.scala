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

import scala.annotation.tailrec
import swave.core.{ StreamEnv, PipeElem }
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

  type State = Stage.State

  private[this] var _intercepting = false
  private[this] var _sealed = false
  private[this] var _state: State = _
  private[this] var _mbs: Int = _
  private[this] var _inportState: InportStates = _
  private[this] var _buffer: ResizableRingBuffer[AnyRef] = _ // TODO: evaluate inlining vs. splitting into 3 more narrowly typed buffers

  protected final def initialState(s: State): Unit = _state = s

  protected final def configureFrom(env: StreamEnv): Unit =
    _mbs = env.settings.maxBatchSize

  final def subscribe()(implicit from: Outport) =
    if (!_intercepting) {
      _intercepting = _state.interceptWhileHandling
      _subscribe(from)
      handleInterceptions()
    } else storeInterception(StageStatics.LONGS(0), from)

  private def _subscribe(from: Outport): Unit =
    if (_state.subscribe ne null) _state = _state.subscribe(from)
    else illegalState(s"Unexpected subscribe() from out '$from' in $this")

  final def request(n: Long)(implicit from: Outport) =
    if (!_intercepting) {
      _intercepting = _state.interceptWhileHandling
      _request(n, from)
      handleInterceptions()
    } else storeInterception(StageStatics.LONGS(1), if (n < 16) StageStatics.LONGS(n.toInt) else new java.lang.Long(n), from)

  private def _request(n: Long, from: Outport): Unit =
    if (_state.request ne null) {
      val count =
        if (n > _mbs) {
          from.asInstanceOf[Stage].updateInportState(this, n)
          _mbs
        } else n.toInt
      _state = _state.request(count, from)
    } else illegalState(s"Unexpected request($n) from out '$from' in $this")

  final def cancel()(implicit from: Outport) =
    if (!_intercepting) {
      _intercepting = _state.interceptWhileHandling
      from.asInstanceOf[Stage].clearInportState(this)
      _cancel(from)
      handleInterceptions()
    } else {
      from.asInstanceOf[Stage].clearInportState(this)
      storeInterception(StageStatics.LONGS(2), from)
    }

  private def _cancel(from: Outport): Unit =
    if (_state.cancel ne null) _state = _state.cancel(from)
    else illegalState(s"Unexpected cancel() from out '$from' in $this")

  final def onSubscribe()(implicit from: Inport) =
    if (!_intercepting) {
      _intercepting = _state.interceptWhileHandling
      _onSubscribe(from)
      handleInterceptions()
    } else storeInterception(StageStatics.LONGS(3), from)

  private def _onSubscribe(from: Inport): Unit = _state =
    if (_state.onSubscribe ne null) _state.onSubscribe(from)
    else illegalState(s"Unexpected onSubscribe() from in '$from' in $this")

  final def onNext(elem: AnyRef)(implicit from: Inport) =
    if (!_intercepting) {
      _intercepting = _state.interceptWhileHandling
      _onNext(elem, from)
      handleInterceptions()
    } else storeInterception(StageStatics.LONGS(4), elem, from)

  private def _onNext(elem: AnyRef, from: Inport): Unit = {
    @tailrec def rec(last: InportStates, current: InportStates): State =
      if (current ne null) {
        if (current.in eq from) {
          val next = _state.onNext(elem, from)
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
          next
        } else rec(current, current.tail)
      } else _state.onNext(elem, from)

    if (_state.onNext ne null) _state = rec(null, _inportState)
    else illegalState(s"Unexpected onNext($elem) from in '$from' in $this")
  }

  final def onComplete()(implicit from: Inport) =
    if (!_intercepting) {
      _intercepting = _state.interceptWhileHandling
      _onComplete(from)
      handleInterceptions()
    } else storeInterception(StageStatics.LONGS(5), from)

  private def _onComplete(from: Inport): Unit =
    if (_state.onComplete ne null) {
      clearInportState(from)
      _state = _state.onComplete(from)
    } else illegalState(s"Unexpected onComplete() from in '$from' in $this")

  final def onError(error: Throwable)(implicit from: Inport) =
    if (!_intercepting) {
      _intercepting = _state.interceptWhileHandling
      _onError(error, from)
      handleInterceptions()
    } else storeInterception(StageStatics.LONGS(6), error, from)

  private def _onError(error: Throwable, from: Inport): Unit =
    if (_state.onError ne null) {
      clearInportState(from)
      _state = _state.onError(error, from)
    } else illegalState(s"Unexpected onError($error) from in '$from' in $this")

  final def xSeal(ctx: RunContext): Unit =
    if (!_sealed) {
      if (_state.xSeal ne null) {
        _sealed = true
        _state = _state.xSeal(ctx)
        if (_state.xStart ne null) _intercepting = true
      } else illegalState(s"Unexpected xSeal(...) in $this")
    }

  final def xStart(): Unit =
    if (_state.xStart ne null) {
      _state = _state.xStart()
      handleInterceptions()
    } else illegalState(s"Unexpected xStart() in $this")

  final def xRun() =
    if (_state.xRun ne null) _state = _state.xRun()
    else illegalState(s"Unexpected xRun() in $this")

  final def xCleanUp(): Unit =
    if (_state.xCleanUp ne null) _state = _state.xCleanUp()
    else illegalState(s"Unexpected xCleanUp() in $this")

  protected final implicit def self: this.type = this

  protected final def stay(): State = _state

  protected final def stop(): State = {
    _buffer = null // don't hang on to elements
    stopped
  }

  protected final def stopF(out: Outport): State = stop()

  @tailrec protected final def cancelAll[L >: Null <: AbstractInportList[L]](ins: L, except: Inport = null): Unit =
    if (ins ne null) {
      if (ins.in ne except) ins.in.cancel()
      cancelAll(ins.tail)
    }

  protected final def cancelAllAndStopComplete[L >: Null <: AbstractInportList[L]](ins: L, except: Inport, out: Outport): State = {
    cancelAll(ins, except)
    stopComplete(out)
  }

  protected final def stopCancel(in: Inport): State = {
    in.cancel()
    stop()
  }

  protected final def stopCancel[L >: Null <: AbstractInportList[L]](ins: L, except: Inport = null): State = {
    cancelAll(ins, except)
    stop()
  }

  protected final def stopCancelF(in: Inport)(out: Outport): State = stopCancel(in)

  protected final def stopCancelF[L >: Null <: AbstractInportList[L]](ins: L)(out: Outport): State = stopCancel(ins)

  protected final def stopComplete(out: Outport): State = {
    out.onComplete()
    stop()
  }

  protected final def stopCompleteF(out: Outport)(in: Inport): State = stopComplete(out)

  protected final def cancelAllAndStopCompleteF[L >: Null <: AbstractInportList[L]](ins: L, out: Outport)(in: Inport): State = {
    cancelAll(ins, except = in)
    stopComplete(out)
  }

  protected final def stopError(e: Throwable, out: Outport): State = {
    out.onError(e)
    stop()
  }

  protected final def stopErrorF(out: Outport)(e: Throwable, in: Inport): State =
    stopError(e, out)

  protected final def cancelAllAndStopErrorF[L >: Null <: AbstractInportList[L]](ins: L, out: Outport)(e: Throwable, in: Inport): State = {
    cancelAll(ins, except = in)
    stopError(e, out)
  }

  protected final def illegalState(msg: String) = throw new IllegalStateException(msg)

  private def storeInterception(signal: java.lang.Long, arg: AnyRef): Unit =
    if (!buffer.write(signal, arg))
      illegalState(s"Interception buffer overflow on signal '$signal($arg)' in $this")

  private def storeInterception(signal: java.lang.Long, arg0: AnyRef, arg1: AnyRef): Unit =
    if (!buffer.write(signal, arg0, arg1))
      illegalState(s"Interception buffer overflow on signal '$signal($arg0, $arg1)' in $this")

  private def buffer = {
    if (_buffer eq null) {
      val initialSize = roundUpToNextPowerOf2(_mbs)
      _buffer = new ResizableRingBuffer(initialSize, initialSize << 4)
    }
    _buffer
  }

  @tailrec private def handleInterceptions(): Unit =
    if ((_buffer ne null) && _buffer.nonEmpty) {
      def read() = _buffer.unsafeRead()
      read().asInstanceOf[java.lang.Long].intValue() match {
        case 0 ⇒ _subscribe(read().asInstanceOf[Stage])
        case 1 ⇒ _request(read().asInstanceOf[java.lang.Long].longValue(), read().asInstanceOf[Stage])
        case 2 ⇒ _cancel(read().asInstanceOf[Stage])
        case 3 ⇒ _onSubscribe(read().asInstanceOf[Stage])
        case 4 ⇒ _onNext(read(), read().asInstanceOf[Stage])
        case 5 ⇒ _onComplete(read().asInstanceOf[Stage])
        case 6 ⇒ _onError(read().asInstanceOf[Throwable], read().asInstanceOf[Stage])
      }
      handleInterceptions()
    } else _intercepting = false

  private def updateInportState(in: Inport, requested: Long): Unit = {
    require(requested > _mbs)
    @tailrec def rec(current: InportStates): Unit =
      if (current ne null) {
        if (current.in eq in) current.remaining = current.remaining ⊹ requested
        else rec(current.tail)
      } else _inportState = new InportStates(in, _inportState, _mbs, requested - _mbs)
    rec(_inportState)
  }

  private def clearInportState(in: Inport): Unit =
    _inportState = _inportState.remove(in)

  protected final def fullState(
    name: String,
    interceptWhileHandling: Boolean = true,
    subscribe: Outport ⇒ State = null,
    request: (Int, Outport) ⇒ State = null,
    cancel: Outport ⇒ State = null,
    onSubscribe: Inport ⇒ State = null,
    onNext: (AnyRef, Inport) ⇒ State = null,
    onComplete: Inport ⇒ State = null,
    onError: (Throwable, Inport) ⇒ State = null,
    xSeal: RunContext ⇒ State = null,
    xStart: () ⇒ State = null,
    xRun: () ⇒ State = null,
    xCleanUp: () ⇒ State = null) =
    new State(name, interceptWhileHandling, subscribe, request, cancel, onSubscribe, onNext, onComplete, onError,
      xSeal, xStart, xRun, xCleanUp)

  protected final def stateName: String = _state.name

  override def toString = s"${getClass.getSimpleName}@${identityHash(this)}/$stateName"

  final def stopped =
    fullState(
      name = "STOPPED",
      interceptWhileHandling = false,

      subscribe = _ ⇒ stay(),
      request = (_, _) ⇒ stay(),
      cancel = _ ⇒ stay(),
      onSubscribe = _ ⇒ stay(),
      onNext = (_, _) ⇒ stay(),
      onComplete = _ ⇒ stay(),
      onError = (_, _) ⇒ stay(),
      xSeal = _ ⇒ stay(),
      xStart = () ⇒ stay(),
      xRun = () ⇒ stay(),
      xCleanUp = () ⇒ stay())
}

private[swave] object Stage {

  private[stages] final class State private[Stage] (
    val name: String,
    val interceptWhileHandling: Boolean,
    val subscribe: Outport ⇒ State,
    val request: (Int, Outport) ⇒ State,
    val cancel: Outport ⇒ State,
    val onSubscribe: Inport ⇒ State,
    val onNext: (AnyRef, Inport) ⇒ State,
    val onComplete: Inport ⇒ State,
    val onError: (Throwable, Inport) ⇒ State,
    val xSeal: RunContext ⇒ State,
    val xStart: () ⇒ State,
    val xRun: () ⇒ State,
    val xCleanUp: () ⇒ State)

  private class InportStates(in: Inport, tail: InportStates,
    var pending: Int, // already requested from `in` but not yet received
    var remaining: Long) // already requested by us but not yet forwarded to `in`
      extends AbstractInportList[InportStates](in, tail)

  private[stages] class OutportStates(out: Outport, tail: OutportStates,
    var remaining: Long) // requested by this `out` but not yet delivered, i.e. unfulfilled demand
      extends AbstractOutportList[OutportStates](out, tail)
}