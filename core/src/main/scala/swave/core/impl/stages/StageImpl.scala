/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import scala.annotation.{compileTimeOnly, tailrec}
import swave.core.impl.util.AbstractInportList
import swave.core.util._
import swave.core.impl._
import swave.core._

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
  import StageImpl.RequestBacklogList

  type State = Int // semantic alias

  private[this] var _interceptionLevel: Int             = _
  private[this] var _state: State                       = _ // current state; the STOPPED state is always encoded as zero
  private[this] var _requestBacklog: RequestBacklogList = _
  private[this] var _region: Region                     = _

  // bit mask holding misc flags
  // bits 0-29: set if respective state requires interception support
  // bit    30: set if stage required full interceptions, i.e. including the `from` stage
  // bit    31: set if stage `request` signals should be intercepted *before* reaching this stage
  //            because it usually produces elements to the requesting downstream synchronously
  protected final var flags: Int = _

  protected final implicit def self: this.type               = this
  protected final def initialState(s: State): Unit           = _state = s
  protected final def stay(): State                          = _state
  protected final def illegalState(msg: String)              = new IllegalStateException(msg + " in " + this)
  protected final def setInterceptionLevel(level: Int): Unit = _interceptionLevel = level

  final def region: Region       = _region
  final def resetRegion(): Unit  = _region = null
  final def stageImpl: StageImpl = this
  final def isSealed: Boolean    = region ne null
  final def isStopped: Boolean   = _state == 0

  def stateName: String = s"<unknown id ${_state}>"
  override def toString = s"${getClass.getSimpleName}@${identityHash(this)}/$stateName"

  /////////////////////////////////////// SUBSCRIBE ///////////////////////////////////////

  final def subscribe()(implicit from: Outport): Unit =
    if (notIntercepting) _subscribe(from)
    else interceptSubscribe(from)

  final def _subscribe(from: Outport): Unit = {
    val mark = enterInterceptionLevel()
    _state = _subscribe0(from)
    exitInterceptionLevel(mark)
  }

  protected def _subscribe0(from: Outport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒
        throw new IllegalReuseException(
          s"Port already connected in $this. Are you trying to reuse a stage instance?",
          illegalState(s"Unexpected subscribe() from out '$from'"))
    }

  protected final def interceptSubscribe(from: Outport): Unit = {
    registerInterception()
    region.impl.enqueueSubscribeInterception(this, if (fullInterceptions) from else null)
  }

  /////////////////////////////////////// REQUEST ///////////////////////////////////////

  final def request(n: Long)(implicit from: Outport): Unit = {
    val mbs = region.mbs
    val count =
      if (n > mbs) {
        // if we get here then we are still running on the thread of the `from` Outport,
        // not on the thread of `this` stage's runner, so we can directly mutate the `from` stage
        from.stageImpl.updateRequestBacklog(this, n)
        mbs
      } else n.toInt

    if (!interceptAllRequests && notIntercepting) _request(count, from)
    else interceptRequest(count, from)
  }

  final def _request(n: Int, from: Outport): Unit = {
    val mark = enterInterceptionLevel()
    _state = _request0(n, from)
    exitInterceptionLevel(mark)
  }

  protected def _request0(n: Int, from: Outport): State = stay()

  protected final def interceptRequest(n: Int, from: Outport): Unit = {
    registerInterception()
    region.impl.enqueueRequestInterception(this, n, if (fullInterceptions) from else null)
  }

  protected final def requestF(in: Inport)(n: Int, from: Outport): State = {
    in.request(n.toLong)
    stay()
  }

  /////////////////////////////////////// CANCEL ///////////////////////////////////////

  final def cancel()(implicit from: Outport): Unit = {
    // when we get here then we are still running on the thread of the `from` Outport,
    // not on the thread of `this` stage's runner, so we can directly mutate the `from` stage
    from.stageImpl.clearRequestBacklog(this)
    if (notIntercepting) _cancel(from)
    else interceptCancel(from)
  }

  final def _cancel(from: Outport): Unit = {
    val mark = enterInterceptionLevel()
    _state = _cancel0(from)
    exitInterceptionLevel(mark)
  }

  protected def _cancel0(from: Outport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ throw illegalState(s"Unexpected cancel() from out '$from'")
    }

  protected final def interceptCancel(from: Outport): Unit = {
    registerInterception()
    region.impl.enqueueCancelInterception(this, if (fullInterceptions) from else null)
  }

  /////////////////////////////////////// ONSUBSCRIBE ///////////////////////////////////////

  final def onSubscribe()(implicit from: Inport): Unit =
    if (notIntercepting) _onSubscribe(from)
    else interceptOnSubscribe(from)

  final def _onSubscribe(from: Inport): Unit = {
    val mark = enterInterceptionLevel()
    _state = _onSubscribe0(from)
    exitInterceptionLevel(mark)
  }

  protected def _onSubscribe0(from: Inport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒
        throw new IllegalReuseException(
          s"Port already connected in $this. Are you trying to reuse a stage instance?",
          illegalState(s"Unexpected onSubscribe() from out '$from'"))
    }

  protected final def interceptOnSubscribe(from: Inport): Unit = {
    registerInterception()
    region.impl.enqueueOnSubscribeInterception(this, if (fullInterceptions) from else null)
  }

  /////////////////////////////////////// ONNEXT ///////////////////////////////////////

  final def onNext(elem: AnyRef)(implicit from: Inport): Unit =
    if (notIntercepting) _onNext(elem, from)
    else interceptOnNext(elem, from)

  final def _onNext(elem: AnyRef, from: Inport): Unit = {
    @tailrec def rec(current: RequestBacklogList, last: RequestBacklogList): Unit =
      if (current.nonEmpty) {
        val currentIn = current.in
        if ((from eq null) || (currentIn eq from)) {
          _state = _onNext0(elem, from)
          if (current.tail ne current) { // is `current` not cancelled? (tail eq self signals "cancelled")
            val newPending = current.pending - 1
            if (newPending == 0) {
              val mbs          = region.mbs
              val newRemaining = current.remaining - mbs
              val n =
                if (newRemaining > 0) {
                  current.pending = mbs
                  current.remaining = newRemaining
                  mbs.toLong
                } else {
                  if (last ne null) last.tail = current.tail else _requestBacklog = current.tail
                  current.remaining
                }
              currentIn.request(n)
            } else current.pending = newPending
          }
        } else rec(current.tail, current)
      } else _state = _onNext0(elem, from)

    val mark = enterInterceptionLevel()
    rec(_requestBacklog, null)
    exitInterceptionLevel(mark)
  }

  protected def _onNext0(elem: AnyRef, from: Inport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ throw illegalState(s"Unexpected onNext($elem) from out '$from'")
    }

  protected final def interceptOnNext(elem: AnyRef, from: Inport): Unit = {
    registerInterception()
    region.impl.enqueueOnNextInterception(this, elem, if (fullInterceptions) from else null)
  }

  protected final def onNextF(out: Outport)(elem: AnyRef, from: Inport): State = {
    out.onNext(elem)
    stay()
  }

  /////////////////////////////////////// ONCOMPLETE ///////////////////////////////////////

  final def onComplete()(implicit from: Inport): Unit =
    if (notIntercepting) _onComplete(from)
    else interceptOnComplete(from)

  final def _onComplete(from: Inport): Unit = {
    val mark = enterInterceptionLevel()
    clearRequestBacklog(from)
    _state = _onComplete0(from)
    exitInterceptionLevel(mark)
  }

  protected def _onComplete0(from: Inport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ throw illegalState(s"Unexpected onComplete() from out '$from'")
    }

  protected final def interceptOnComplete(from: Inport): Unit = {
    registerInterception()
    region.impl.enqueueOnCompleteInterception(this, if (fullInterceptions) from else null)
  }

  /////////////////////////////////////// ONERROR ///////////////////////////////////////

  final def onError(error: Throwable)(implicit from: Inport): Unit =
    if (notIntercepting) _onError(error, from)
    else interceptOnError(error, from)

  final def _onError(error: Throwable, from: Inport): Unit = {
    val mark = enterInterceptionLevel()
    clearRequestBacklog(from)
    _state = _onError0(error, from)
    exitInterceptionLevel(mark)
  }

  protected def _onError0(error: Throwable, from: Inport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ throw illegalState(s"Unexpected onError($error) from out '$from'")
    }

  protected final def interceptOnError(error: Throwable, from: Inport): Unit = {
    registerInterception()
    region.impl.enqueueOnErrorInterception(this, error, if (fullInterceptions) from else null)
  }

  /////////////////////////////////////// XSEAL ///////////////////////////////////////

  final def xSeal(region: Region): Unit =
    if (!isSealed) {
      _region = region
      _state = _xSeal()
      if (_region ne null)
        _region.impl.registerStageSealed() // don't call `region.impl...` here as `_region` might now be different!

      @tailrec def sealBoundaries(remaining: List[Module.Boundary]): Unit =
        if (remaining.nonEmpty) {
          val s = remaining.head.stage.stageImpl
          s.xSeal(region)
          sealBoundaries(remaining.tail)
        }
      @tailrec def sealModules(remaining: List[Module.ID]): Unit =
        if (remaining.nonEmpty) {
          if (remaining.head.markSealed()) sealBoundaries(remaining.head.boundaries)
          sealModules(remaining.tail)
        }
      sealModules(boundaryOf)
    }

  protected def _xSeal(): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒
        throw new UnclosedStreamGraphException(s"Unconnected Port in $this", illegalState("Unexpected xSeal()"))
    }

  /////////////////////////////////////// XSTART ///////////////////////////////////////

  final def xStart(): Unit =
    if (!isStopped) {
      // if the current state has an xStart handler (which it must) and defines `intercept = true` (the default)
      // then we currently have an interceptionLevel > 0
      val mark = interceptionNeeded
      _state = _xStart()
      exitInterceptionLevel(mark)
    }

  protected def _xStart(): State = throw illegalState(s"Unexpected xStart()")

  /////////////////////////////////////// XEVENT ///////////////////////////////////////

  final def xEvent(ev: AnyRef): Unit =
    if (notIntercepting) _xEvent(ev)
    else interceptXEvent(ev)

  final def _xEvent(ev: AnyRef): Unit = {
    val mark = enterInterceptionLevel()
    _state = _xEvent0(ev)
    exitInterceptionLevel(mark)
  }

  protected def _xEvent0(ev: AnyRef): State =
    if (isStopped) stay() else throw illegalState(s"Unexpected xEvent($ev)")

  protected final def interceptXEvent(ev: AnyRef): Unit = {
    registerInterception()
    region.impl.enqueueXEventInterception(this, ev)
  }

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
                            xSeal: () ⇒ State = null,
                            xStart: () ⇒ State = null,
                            xEvent: AnyRef ⇒ State = null): State = 0

  /////////////////////////////////////// STOPPERS ///////////////////////////////////////

  protected final def stop(e: Throwable = null): State = {
    if (isSealed) region.impl.registerStageStopped()
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

  private def interceptAllRequests: Boolean = flags < 0 // test if bit 31 is set
  private def fullInterceptions: Boolean    = (flags & 0x40000000) != 0 // test if bit 30 is set
  private def notIntercepting: Boolean      = _interceptionLevel == 0
  private def registerInterception(): Unit  = _interceptionLevel += 1
  private def interceptionNeeded: Int       = (flags >> _state) & 1
  private def enterInterceptionLevel(): Int = {
    val mark = interceptionNeeded
    _interceptionLevel = math.max(_interceptionLevel, mark)
    mark
  }
  private def exitInterceptionLevel(mark: Int): Unit = _interceptionLevel -= mark

  private def updateRequestBacklog(in: Inport, requested: Long): Unit = {
    @tailrec def rec(current: RequestBacklogList): Unit =
      if (current ne null) {
        if (current.in eq in) current.remaining = current.remaining ⊹ requested
        else rec(current.tail)
      } else {
        val mbs = region.mbs
        _requestBacklog = new RequestBacklogList(in, _requestBacklog, mbs, requested - mbs)
      }
    rec(_requestBacklog)
  }

  private def clearRequestBacklog(in: Inport): Unit =
    _requestBacklog = _requestBacklog.remove(in)
}

private[swave] object StageImpl {

  private final class RequestBacklogList(in: Inport,
                                         tail: RequestBacklogList,
                                         var pending: Int, // already requested from `in` but not yet received
                                         var remaining: Long) // already requested by us but not yet forwarded to `in`
      extends AbstractInportList[RequestBacklogList](in, tail)
}
