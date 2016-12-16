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

  initialState(awaitingSubscribe(StreamTermination.None, null))

  def awaitingSubscribe(term: StreamTermination, timer: Cancellable): State = state(
    subscribe = from ⇒ {
      _outputStages = from.stageImpl :: Nil
      from.onSubscribe()
      ready(from, term, timer)
    },

    onComplete = _ => awaitingSubscribe(term transitionTo StreamTermination.Completed, timer),
    onError = (e, _) => awaitingSubscribe(term transitionTo StreamTermination.Error(e), timer),

    xEvent = {
      case EnableSubStreamStartTimeout if timer eq null =>
        awaitingSubscribe(term, in.region.impl.scheduleSubStreamStartTimeout(this))
      case RunContext.SubStreamStartTimeout => stopCancel(in)
    })

  def ready(out: Outport, term: StreamTermination, timer: Cancellable): State = state(
    xSeal = () ⇒ {
      region.impl.becomeSubRegionOf(in.region)
      region.impl.registerForXStart(this)
      out.xSeal(region)
      awaitingXStart(out, term, timer)
    },

    onComplete = _ => ready(out, term transitionTo StreamTermination.Completed, timer),
    onError = (e, _) => ready(out, term transitionTo StreamTermination.Error(e), timer),

    xEvent = {
      case EnableSubStreamStartTimeout if timer eq null =>
        ready(out, term, in.region.impl.scheduleSubStreamStartTimeout(this))
      case RunContext.SubStreamStartTimeout => stopCancel(in)
    })

  def awaitingXStart(out: Outport, term: StreamTermination, timer: Cancellable): State = state(
    intercept = false,

    request = (n, _) => { interceptRequest(n.toLong, out); stay() },
    cancel = _ => { interceptCancel(out); stay() },

    xStart = () => {
      if (timer ne null) timer.cancel()
      term match {
        case StreamTermination.None => running(out)
        case StreamTermination.Error(e) => stopError(e, out)
        case _ => stopComplete(out)
      }
    },

    onComplete = _ => awaitingXStart(out, term transitionTo StreamTermination.Completed, timer),
    onError = (e, _) => awaitingXStart(out, term transitionTo StreamTermination.Error(e), timer),

    xEvent = {
      case EnableSubStreamStartTimeout if timer eq null =>
        awaitingXStart(out, term, in.region.impl.scheduleSubStreamStartTimeout(this))
      case RunContext.SubStreamStartTimeout => stopCancel(in)
    })

  def running(out: Outport) = state(
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
