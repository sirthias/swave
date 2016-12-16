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
@StageImplementation(fullInterceptions = true)
private[core] final class SubDrainStage(val out: StageImpl) extends DrainStage {
  import SubDrainStage._

  def kind = Stage.Kind.Drain.Sub(out)

  initialState(awaitingOnSubscribe(false, null))

  def awaitingOnSubscribe(cancelled: Boolean, timer: Cancellable): State = state(
    cancel = _ => awaitingOnSubscribe(cancelled = true, timer),

    onSubscribe = from ⇒ {
      _inputStages = from.stageImpl :: Nil
      xEvent(DoOnSubscribe) // schedule event for after the state transition
      ready(from, cancelled, timer)
    },

    xEvent = {
      case EnableSubStreamStartTimeout if timer eq null =>
        awaitingOnSubscribe(cancelled, out.region.impl.scheduleSubStreamStartTimeout(this))
      case RunContext.SubStreamStartTimeout => timeoutFail("subscribed")
    })

  def ready(in: Inport, cancelled: Boolean, timer: Cancellable): State = state(
    cancel = _ => ready(in, cancelled = true, timer),

    xSeal = () ⇒ {
      region.impl.becomeSubRegionOf(out.region)
      region.impl.registerForXStart(this)
      in.xSeal(region)
      awaitingXStart(in, cancelled, timer)
    },

    xEvent = {
      case DoOnSubscribe => { out.onSubscribe(); stay() }
      case EnableSubStreamStartTimeout if timer eq null =>
        ready(in, cancelled, out.region.impl.scheduleSubStreamStartTimeout(this))
      case RunContext.SubStreamStartTimeout => timeoutFail("sealed")
    })

  def awaitingXStart(in: Inport, cancelled: Boolean, timer: Cancellable): State = state(
    intercept = false,

    request = (n, _) => { interceptRequest(n.toLong, out); stay() },
    cancel = _ => awaitingXStart(in, cancelled = true, timer),

    xStart = () => {
      if (timer ne null) timer.cancel()
      if (cancelled) stopCancel(in)
      else running(in)
    },

    onNext = (elem, _) => { interceptOnNext(elem, out); stay() },
    onComplete = _ => { interceptOnComplete(out); stay() },
    onError = (e, _) => { interceptOnError(e, out); stay() },

    xEvent = {
      case EnableSubStreamStartTimeout if timer eq null =>
        awaitingXStart(in, cancelled, region.impl.scheduleSubStreamStartTimeout(this))
      case RunContext.SubStreamStartTimeout => timeoutFail("started")
    })

  def running(in: Inport) = state(
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
  private case object DoOnSubscribe
}
