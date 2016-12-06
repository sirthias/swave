/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.spout

import swave.core.{Cancellable, Stage}
import swave.core.impl.{Outport, RunSupport, StreamRunner}
import swave.core.impl.stages.{SpoutStage, StageImpl, StreamTermination}
import swave.core.macros.StageImplementation
import SubSpoutStage._

// format: OFF
@StageImplementation
private[core] class SubSpoutStage(runContext: RunSupport.RunContext, val in: StageImpl) extends SpoutStage {

  def kind = Stage.Kind.Spout.Sub(in)

  initialState(awaitingSubscribe(StreamTermination.None, null))

  def awaitingSubscribe(term: StreamTermination, timer: Cancellable): State = state(
    subscribe = from ⇒ {
      if (timer ne null) timer.cancel()
      _outputStages = from.stageImpl :: Nil
      from.onSubscribe()
      ready(from, term)
    },

    onComplete = _ => awaitingSubscribe(term transitionTo StreamTermination.Completed, timer),
    onError = (e, _) => awaitingSubscribe(term transitionTo StreamTermination.Error(e), timer),

    xEvent = {
      case EnableSubscriptionTimeout if timer eq null =>
        val t = runContext.scheduleSubscriptionTimeout(this)
        awaitingSubscribe(term, t)
      case RunSupport.SubscriptionTimeout =>
        stopCancel(in)
    })

  def ready(out: Outport, term: StreamTermination): State = state(
    xSeal = ctx ⇒ {
      configureFrom(ctx)
      ctx.assignRunContext(runContext)
      if (in.hasRunner) ctx.registerRunnerAssignment(StreamRunner.Assignment.Runner(this, in.runner))
      out.xSeal(ctx)
      if (term != StreamTermination.None) {
        ctx.registerForXStart(this)
        awaitingXStart(out, term)
      } else running(out)
    },

    onComplete = _ => ready(out, term transitionTo StreamTermination.Completed),
    onError = (e, _) => ready(out, term transitionTo StreamTermination.Error(e)),

    xEvent = {
      case EnableSubscriptionTimeout => stay() // ignore
      case RunSupport.SubscriptionTimeout => stay() // ignore
    })

  def awaitingXStart(out: Outport, termination: StreamTermination): State = state(
    xStart = () => {
      termination match {
        case StreamTermination.Error(e) => stopError(e, out)
        case _ => stopComplete(out)
      }
    },

    onComplete = _ => stay(),
    onError = (e, _) => stay(),

    xEvent = {
      case EnableSubscriptionTimeout => stay() // ignore
      case RunSupport.SubscriptionTimeout => stay() // ignore
    })

  def running(out: Outport) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),
    onNext = onNextF(out),
    onComplete = stopCompleteF(out),
    onError = stopErrorF(out),

    xEvent = {
      case EnableSubscriptionTimeout => stay() // ignore
      case RunSupport.SubscriptionTimeout => stay() // ignore
    })
}

private[core] object SubSpoutStage {

  case object EnableSubscriptionTimeout
}
