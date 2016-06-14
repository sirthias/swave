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

package swave.core.impl.stages.source

import scala.concurrent.duration.Duration
import swave.core.{ Cancellable, PipeElem }
import swave.core.impl.{ Outport, RunContext }
import swave.core.impl.stages.{ StreamTermination, Stage }
import swave.core.macros.StageImpl
import SubSourceStage._

// format: OFF
@StageImpl
private[core] final class SubSourceStage(ctx: RunContext, val in: Stage, subscriptionTimeout: Duration) extends SourceStage
  with PipeElem.Source.Sub {

  def pipeElemType: String = "sub"
  def pipeElemParams: List[Any] = in :: Nil

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

private[core] object SubSourceStage {

  case object EnableSubscriptionTimeout
}

