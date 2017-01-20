/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.spout

import swave.core.{Cancellable, Stage}
import swave.core.impl.{Outport, RunContext}
import swave.core.impl.stages.{SpoutStage, StageImpl, StreamTermination}
import swave.core.macros.StageImplementation
import SubSpoutStage._

// format: OFF
@StageImplementation
private[core] class SubSpoutStage(val in: StageImpl) extends SpoutStage {

  def kind = Stage.Kind.Spout.Sub(in)

  initialState(connecting(StreamTermination.None, null))

  private var out: Outport = _

  def connecting(term: StreamTermination, timer: Cancellable): State = state(
    intercept = false,

    subscribe = from ⇒ {
      if (out eq null) {
        out = from
        _outputStages = from.stageImpl :: Nil
        from.onSubscribe()
        stay()
      } else throw illegalState("Double subscribe(" + from + ')')
    },

    xSeal = () ⇒ {
      if (out ne null) {
        region.impl.becomeSubRegionOf(in.region)
        region.impl.registerForXStart(this)
        out.xSeal(region)
        awaitingXStart(term, timer)
      } else throw illegalState("Unexpected xSeal(...) (unconnected downstream)")
    },

    onComplete = _ => connecting(term transitionTo StreamTermination.Completed, timer),
    onError = (e, _) => connecting(term transitionTo StreamTermination.Error(e), timer),

    xEvent = {
      case EnableSubStreamStartTimeout if timer eq null =>
        connecting(term, in.region.impl.scheduleSubStreamStartTimeout(this))
      case RunContext.SubStreamStartTimeout => stopCancel(in)
    })

  def awaitingXStart(term: StreamTermination, timer: Cancellable): State = state(
    intercept = false,

    request = (n, _) => { interceptRequest(n, out); stay() },
    cancel = _ => { interceptCancel(out); stay() },

    xStart = () => {
      if (timer ne null) timer.cancel()
      term match {
        case StreamTermination.None => running()
        case StreamTermination.Error(e) => stopError(e, out)
        case _ => stopComplete(out)
      }
    },

    onComplete = _ => awaitingXStart(term transitionTo StreamTermination.Completed, timer),
    onError = (e, _) => awaitingXStart(term transitionTo StreamTermination.Error(e), timer),

    xEvent = {
      case EnableSubStreamStartTimeout if timer eq null =>
        awaitingXStart(term, in.region.impl.scheduleSubStreamStartTimeout(this))
      case RunContext.SubStreamStartTimeout => stopCancel(in)
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
}

private[core] object SubSpoutStage {

  case object EnableSubStreamStartTimeout
}
