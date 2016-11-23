/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import scala.annotation.{compileTimeOnly, tailrec}
import scala.concurrent.Promise
import swave.core.impl.util.{AbstractInportList, ResizableRingBuffer}
import swave.core.{IllegalReuseException, Module, UnclosedStreamGraphException}
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
  * - xEvent
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
  * - must throw exception upon reception of any non `x...` signal
  * - must ignore `xSeal`
  * - upon reception of `xStart`:
  *   - may send one or more non-x signals
  *   - must transition to RUNNING
  *   - must dequeue all intercepted signals
  *
  * RUNNING (potentially several states)
  * - must throw exception upon reception of `xStart`
  * - must ignore `xSeal`
  * - upon signal reception:
  *   - may send one or more signals
  *   - must queue (intercept) all signals while handling one signal instance
  *   - must dequeue all intercepted signals after having finished handling one signal instance
  *   - may transition to STOPPED
  *
  * STOPPED (single state)
  * - ignore all signals
  */
private[swave] abstract class StageImpl extends PortImpl {
  import StageImpl.InportContext

  type State = Int // semantic alias

  // TODO: evaluate moving the two booleans into the (upper) _state integer bits
  private[this] var _intercepting                        = false
  private[this] var _sealed                              = false
  private[this] var _state: State                        = _ // current state; the STOPPED state is always encoded as zero
  private[this] var _mbs: Int                            = _ // configured max batch size
  private[this] var _inportState: InportContext          = _
  private[this] var _buffer: ResizableRingBuffer[AnyRef] = _
  private[this] var _stopPromise: Promise[Unit]          = _ // TODO: consider removing, i.e. finding a solution that doesn't add a field here
  private[this] var _lastSubscribed: Outport             = _ // needed by `_request` when `fullInterceptions` is off

  private[swave] final var runner: StreamRunner = _ // null -> sync run, non-null -> async run

  // TODO: turn into `def`
  protected final var interceptingStates: Int = _ // bit mask holding a 1 bit for every state which requires interception support

  protected final def configureFrom(ctx: RunContext): Unit = _mbs = ctx.env.settings.maxBatchSize

  protected final implicit def self: this.type = this

  protected final def initialState(s: State): Unit = _state = s

  protected final def stay(): State = _state

  protected final def illegalState(msg: String) = new IllegalStateException(msg + " in " + this)

  protected final def setIntercepting(flag: Boolean): Unit = _intercepting = flag

  override def toString = s"${getClass.getSimpleName}@${identityHash(this)}/$stateName"

  def stateName: String = s"<unknown id ${_state}>"

  final def stage: StageImpl     = this
  final def stageImpl: StageImpl = this

  final def isSealed: Boolean  = _sealed
  final def isStopped: Boolean = _state == 0

  /////////////////////////////////////// SUBSCRIBE ///////////////////////////////////////

  final def subscribe()(implicit from: Outport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _subscribe(from)
      _lastSubscribed = from // if `fullInterceptions` is off then we have only one downstream which we remember here
      handleInterceptions()
    } else storeInterception(Statics._0L, from)

  private def _subscribe(from: Outport): Unit = _state = _subscribe0(from)

  protected def _subscribe0(from: Outport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒
        throw new IllegalReuseException(
          s"Port already connected in $this. Are you trying to reuse a stage instance?",
          illegalState(s"Unexpected subscribe() from out '$from'"))
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
        val effectiveFrom = if (from ne null) from else _lastSubscribed
        effectiveFrom.stageImpl.updateInportState(this, n)
        _mbs
      } else n.toInt
    _state = _request0(count, from)
  }

  protected def _request0(n: Int, from: Outport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ throw illegalState(s"Unexpected request($n) from out '$from'")
    }

  protected final def requestF(in: Inport)(n: Int, from: Outport): State = {
    in.request(n.toLong)
    stay()
  }

  /////////////////////////////////////// CANCEL ///////////////////////////////////////

  final def cancel()(implicit from: Outport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      from.stageImpl.clearInportState(this)
      _cancel(from)
      handleInterceptions()
    } else {
      from.stageImpl.clearInportState(this)
      storeInterception(Statics._2L, from)
    }

  private def _cancel(from: Outport): Unit = _state = _cancel0(from)

  protected def _cancel0(from: Outport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ throw illegalState(s"Unexpected cancel() from out '$from'")
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
      case _ ⇒
        throw new IllegalReuseException(
          s"Port already connected in $this. Are you trying to reuse a stage instance?",
          illegalState(s"Unexpected onSubscribe() from out '$from'"))
    }

  /////////////////////////////////////// ONNEXT ///////////////////////////////////////

  final def onNext(elem: AnyRef)(implicit from: Inport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _onNext(elem, from)
      handleInterceptions()
    } else storeInterception(Statics._4L, elem, from)

  @tailrec
  private def _onNext(elem: AnyRef,
                      from: Inport,
                      last: InportContext = null,
                      current: InportContext = _inportState): Unit =
    if (current.nonEmpty) {
      val currentIn = current.in
      if ((from eq null) || (currentIn eq from)) {
        _state = _onNext0(elem, from)
        if (current.tail ne current) { // is `current` still valid, i.e. not cancelled? (tail eq self is special condition)
          val newPending = current.pending - 1
          if (newPending == 0) {
            val newRemaining = current.remaining - _mbs
            if (newRemaining > 0) {
              current.pending = _mbs
              current.remaining = newRemaining
              currentIn.request(_mbs.toLong)
            } else {
              if (last ne null) last.tail = current.tail else _inportState = current.tail
              currentIn.request(current.remaining)
            }
          } else current.pending = newPending
        }
      } else _onNext(elem, from, current, current.tail)
    } else _state = _onNext0(elem, from)

  protected def _onNext0(elem: AnyRef, from: Inport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ throw illegalState(s"Unexpected onNext($elem) from out '$from'")
    }

  protected final def onNextF(out: Outport)(elem: AnyRef, from: Inport): State = {
    out.onNext(elem)
    stay()
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
      case _ ⇒ throw illegalState(s"Unexpected onComplete() from out '$from'")
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
      case _ ⇒ throw illegalState(s"Unexpected onError($error) from out '$from'")
    }

  /////////////////////////////////////// XSEAL ///////////////////////////////////////

  final def xSeal(ctx: RunContext): Unit =
    if (!_sealed) {
      _sealed = true
      @tailrec def sealBoundaries(remaining: List[Module.Boundary]): Unit =
        if (remaining.nonEmpty) {
          remaining.head.stage.stageImpl.xSeal(ctx)
          sealBoundaries(remaining.tail)
        }
      @tailrec def sealModules(remaining: List[Module.ID]): Unit =
        if (remaining.nonEmpty) {
          if (remaining.head.markSealed()) sealBoundaries(remaining.head.boundaries)
          sealModules(remaining.tail)
        }
      sealModules(boundaryOf)
      _state = _xSeal(ctx)
    }

  protected def _xSeal(ctx: RunContext): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒
        throw new UnclosedStreamGraphException(s"Unconnected Port in $this", illegalState("Unexpected xSeal(...)"))
    }

  /////////////////////////////////////// XSTART ///////////////////////////////////////

  final def xStart(): Unit =
    if (!isStopped) {
      _state = _xStart()
      if (runner ne null) runner.registerStageStart(this)
      handleInterceptions()
    }

  protected def _xStart(): State = throw illegalState(s"Unexpected xStart()")

  /////////////////////////////////////// XEVENT ///////////////////////////////////////

  final def xEvent(ev: AnyRef): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _xEvent(ev)
      handleInterceptions()
    } else storeInterception(Statics._7L, ev)

  private def _xEvent(ev: AnyRef): Unit = _state = _xEvent0(ev)

  protected def _xEvent0(ev: AnyRef): State =
    ev match {
      case StageImpl.RegisterStopPromise(p) ⇒
        if (_stopPromise eq null) {
          if (isStopped) p.success(())
          else _stopPromise = p
        } else p.completeWith(_stopPromise.future)
        stay()
      case _ if isStopped ⇒ stay()
      case _              ⇒ throw illegalState(s"Unexpected xEvent($ev)")
    }

  final def enqueueXEvent(ev: AnyRef): Unit =
    if (runner ne null) runner.enqueueXEvent(this, ev)
    else xEvent(ev)

  /////////////////////////////////////// STATE DESIGNATOR ///////////////////////////////////////

  @compileTimeOnly("`state(...) can only be used as the single implementation expression of a 'State Def'!")
  protected final def state(intercept: Boolean = true,
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

  protected final def stop(e: Throwable = null): State = {
    if (_stopPromise ne null) {
      if (e ne null) _stopPromise.failure(e)
      else _stopPromise.success(())
    }
    if (runner ne null) runner.registerStageStop(this)
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
    stop(e)
  }

  protected final def stopErrorF(out: Outport)(e: Throwable, in: Inport): State =
    stopError(e, out)

  /////////////////////////////////////// CANCEL HELPERS ///////////////////////////////////////

  @tailrec protected final def cancelAll[L >: Null <: AbstractInportList[L]](ins: L, except: Inport = null): Unit =
    if (ins ne null) {
      if (ins.in ne except) ins.in.cancel()
      cancelAll(ins.tail)
    }

  protected final def cancelAllAndStopComplete[L >: Null <: AbstractInportList[L]](ins: L,
                                                                                   except: Inport,
                                                                                   out: Outport): State = {
    cancelAll(ins, except)
    stopComplete(out)
  }

  protected final def cancelAllAndStopCompleteF[L >: Null <: AbstractInportList[L]](ins: L, out: Outport)(
      in: Inport): State = {
    cancelAll(ins, except = in)
    stopComplete(out)
  }

  protected final def cancelAllAndStopErrorF[L >: Null <: AbstractInportList[L]](ins: L, out: Outport)(
      e: Throwable,
      in: Inport): State = {
    cancelAll(ins, except = in)
    stopError(e, out)
  }

  /////////////////////////////////////// PRIVATE ///////////////////////////////////////

  private def interceptionNeeded: Boolean = ((interceptingStates >> _state) & 1) != 0
  private def fullInterceptions: Boolean  = interceptingStates < 0

  private def storeInterception(signal: java.lang.Long, from: Port): Unit =
    if (fullInterceptions) {
      if (!buffer.write(signal, from))
        throw illegalState(s"Interception buffer overflow on signal $signal($from)'")
    } else {
      if (!buffer.write(signal))
        throw illegalState(s"Interception buffer overflow on signal $signal()'")
    }

  private def storeInterception(signal: java.lang.Long, arg: AnyRef): Unit =
    if (!buffer.write(signal, arg))
      throw illegalState(s"Interception buffer overflow on signal $signal($arg)'")

  private def storeInterception(signal: java.lang.Long, arg0: AnyRef, from: Port): Unit =
    if (fullInterceptions) {
      if (!buffer.write(signal, arg0, from))
        throw illegalState(s"Interception buffer overflow on signal $signal($arg0, $from)")
    } else {
      if (!buffer.write(signal, arg0))
        throw illegalState(s"Interception buffer overflow on signal $signal($arg0)")
    }

  private def buffer = {
    if (_buffer eq null) {
      val initialSize = roundUpToPowerOf2(_mbs)
      _buffer = new ResizableRingBuffer(initialSize, initialSize << 4)
    }
    _buffer
  }

  @tailrec private def handleInterceptions(): Unit =
    if ((_buffer ne null) && _buffer.nonEmpty) {
      def read() = _buffer.unsafeRead()
      val signal = read().asInstanceOf[java.lang.Long].intValue()
      def from() = if (fullInterceptions) read().asInstanceOf[StageImpl] else null
      signal match {
        case 0 ⇒ _subscribe(from())
        case 1 ⇒ _request(read().asInstanceOf[java.lang.Long].longValue(), from())
        case 2 ⇒ _cancel(from())
        case 3 ⇒ _onSubscribe(from())
        case 4 ⇒ _onNext(read(), from())
        case 5 ⇒ _onComplete(from())
        case 6 ⇒ _onError(read().asInstanceOf[Throwable], from())
        case 7 ⇒ _xEvent(read())
      }
      handleInterceptions()
    } else _intercepting = false

  private def updateInportState(in: Inport, requested: Long): Unit = {
    @tailrec def rec(current: InportContext): Unit =
      if (current ne null) {
        if (current.in eq in) current.remaining = current.remaining ⊹ requested
        else rec(current.tail)
      } else _inportState = new InportContext(in, _inportState, _mbs, requested - _mbs)
    rec(_inportState)
  }

  private def clearInportState(in: Inport): Unit =
    _inportState = _inportState.remove(in)
}

private[swave] object StageImpl {

  /**
    * Can be sent as an `xEvent` to register the given promise for completion when the stage is stopped.
    * If the stage is already stopped the promise is completed as soon as possible.
    * Note that error completion of the promise is performed on a best-effort basis, i.e. it might
    * happen that the stage was stopped due to an error but the promise is still completed with a `Success`
    * (e.g. when the [[RegisterStopPromise]] command arrives *after* the stage has already been stopped.
    */
  final case class RegisterStopPromise(promise: Promise[Unit])

  private final class InportContext(in: Inport,
                                    tail: InportContext,
                                    var pending: Int, // already requested from `in` but not yet received
                                    var remaining: Long) // already requested by us but not yet forwarded to `in`
      extends AbstractInportList[InportContext](in, tail)
}
