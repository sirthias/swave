/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.drain

import swave.core.impl.stages.{DrainStage, StageImpl}
import swave.core.impl.{Inport, RunContext}
import swave.core.macros.StageImplementation
import swave.core.{Cancellable, Stage, SubStreamStartTimeoutException}

// format: OFF
@StageImplementation(fullInterceptions = true, manualInport = "in")
private[core] final class SubDrainStage(val out: StageImpl) extends DrainStage {
  import SubDrainStage._

  def kind = Stage.Kind.Drain.Sub(out)

  private var in: Inport = _

  initialState(connecting(false, null))

  def connecting(cancelled: Boolean, timer: Cancellable): State = state(
    intercept = false,

    cancel = _ => connecting(cancelled = true, timer),

    onSubscribe = from ⇒ {
      in = from
      _inputStages = from.stageImpl :: Nil
      out.onSubscribe()
      stay()
    },

    xSeal = () ⇒ {
      if (in ne null) {
        region.impl.becomeSubRegionOf(out.region)
        region.impl.registerForXStart(this)
        in.xSeal(region)
        awaitingXStart(cancelled, timer)
      } else throw illegalState("Unexpected xSeal(...) (unconnected upstream)")
    },

    xEvent = {
      case EnableSubStreamStartTimeout if timer eq null =>
        connecting(cancelled, out.region.impl.scheduleSubStreamStartTimeout(this))
      case RunContext.SubStreamStartTimeout => timeoutFail(if (in eq null) "subscribed" else "sealed")
    })

  def awaitingXStart(cancelled: Boolean, timer: Cancellable): State = state(
    intercept = false,

    request = (n, _) => { interceptRequest(n, out); stay() },
    cancel = _ => awaitingXStart(cancelled = true, timer),

    xStart = () => {
      if (timer ne null) timer.cancel()
      if (cancelled) stopCancel(in)
      else running()
    },

    onNext = (elem, _) => { interceptOnNext(elem, out); stay() },
    onComplete = _ => { interceptOnComplete(out); stay() },
    onError = (e, _) => { interceptOnError(e, out); stay() },

    xEvent = {
      case EnableSubStreamStartTimeout if timer eq null =>
        awaitingXStart(cancelled, region.impl.scheduleSubStreamStartTimeout(this))
      case RunContext.SubStreamStartTimeout => timeoutFail("started")
    })

  def running() = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),
    onNext = onNextF(out),
    onComplete = stopCompleteF(out),
    onError = stopErrorF(out),

    xEvent = {
      case EnableSubStreamStartTimeout => stay() // ignore
      case RunContext.SubStreamStartTimeout => stay() // ignore
    })

  private def timeoutFail(s: String) = {
    val msg = s"SubDrainStage wasn't $s within ${out.region.env.settings.subStreamStartTimeout}"
    stopError(new SubStreamStartTimeoutException(msg), out)
  }
}

private[core] object SubDrainStage {

  case object EnableSubStreamStartTimeout
}
