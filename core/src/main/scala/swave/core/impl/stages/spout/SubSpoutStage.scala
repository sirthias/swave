/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.spout

import scala.concurrent.duration.Duration
import swave.core.{ Cancellable, PipeElem }
import swave.core.impl.{ Outport, RunContext }
import swave.core.impl.stages.{ StreamTermination, Stage }
import swave.core.macros.StageImpl
import SubSpoutStage._

// format: OFF
@StageImpl
private[core] final class SubSpoutStage(ctx: RunContext, val in: Stage, subscriptionTimeout: Duration) extends SpoutStage
  with PipeElem.Spout.Sub {

  def pipeElemType: String = "sub"
  def pipeElemParams: List[Any] = in :: subscriptionTimeout :: Nil

  initialState(awaitingSubscribe(StreamTermination.None, null))

  def awaitingSubscribe(termination: StreamTermination, timer: Cancellable): State = state(
    subscribe = from ⇒ {
      if (timer ne null) timer.cancel()
      _outputPipeElem = from.pipeElem
      from.onSubscribe()
      ready(from, termination)
    },

    onComplete = _ => awaitingSubscribe(StreamTermination.Completed, timer),
    onError = (e, _) => awaitingSubscribe(StreamTermination.Error(e), timer),

    xEvent = {
      case EnableSubscriptionTimeout if timer eq null =>
        val t = ctx.scheduleSubscriptionTimeout(this, subscriptionTimeout)
        awaitingSubscribe(termination, t)
      case RunContext.SubscriptionTimeout =>
        stopCancel(in)
    })

  def ready(out: Outport, termination: StreamTermination): State = state(
    xSeal = subCtx ⇒ {
      ctx.attach(subCtx)
      configureFrom(ctx)
      out.xSeal(ctx)
      termination match {
        case StreamTermination.None => running(out)
        case _ =>
          ctx.registerForXStart(this)
          awaitingXStart(out, termination)
      }
    },

    onComplete = _ => ready(out, StreamTermination.Completed),
    onError = (e, _) => ready(out, StreamTermination.Error(e)),

    xEvent = {
      case EnableSubscriptionTimeout => stay() // ignore
      case RunContext.SubscriptionTimeout => stay() // ignore
    })

  def awaitingXStart(out: Outport, termination: StreamTermination) = state(
    xStart = () => {
      termination match {
        case StreamTermination.Error(e) => stopError(e, out)
        case _ => stopComplete(out)
      }
    },

    xEvent = {
      case EnableSubscriptionTimeout => stay() // ignore
      case RunContext.SubscriptionTimeout => stay() // ignore
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
      case RunContext.SubscriptionTimeout => stay() // ignore
    })
}

private[core] object SubSpoutStage {

  case object EnableSubscriptionTimeout
}
