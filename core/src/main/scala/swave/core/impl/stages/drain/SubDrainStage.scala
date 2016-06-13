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

package swave.core.impl.stages.drain

import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import swave.core.impl.stages.Stage
import swave.core.impl.stages.drain.SubDrainStage._
import swave.core.impl.{ Inport, RunContext }
import swave.core.macros.StageImpl
import swave.core.{ SubscriptionTimeoutException, Cancellable, PipeElem }

// format: OFF
@StageImpl
private[core] final class SubDrainStage(ctx: RunContext, val out: Stage, subscriptionTimeout: Duration) extends DrainStage
  with PipeElem.Drain.Sub {

  def pipeElemType: String = "sub"
  def pipeElemParams: List[Any] = out :: Nil

  initialState(awaitingOnSubscribe(false, null))

  def sealAndStart() =
    try ctx.sealAndStartSubStream(this)
    catch {
      case NonFatal(e) => out.onError(e)
    }

  def awaitingOnSubscribe(cancelled: Boolean, timer: Cancellable): State = state(
    cancel = _ => awaitingOnSubscribe(cancelled = true, timer),

    onSubscribe = from ⇒ {
      if (timer ne null) timer.cancel()
      _inputPipeElem = from.pipeElem
      xEvent(DoOnSubscribe) // schedule event for after the state transition
      ready(from, cancelled)
    },

    xEvent = {
      case EnableSubscriptionTimeout if timer eq null =>
        val t = ctx.scheduleSubscriptionTimeout(this, subscriptionTimeout)
        awaitingOnSubscribe(cancelled, t)
      case RunContext.SubscriptionTimeout =>
        val msg = s"Subscription attempt from SubDrainStage timed out after $subscriptionTimeout"
        stopError(new SubscriptionTimeoutException(msg), out)
    })

  def ready(in: Inport, cancelled: Boolean): State = state(
    cancel = _ => ready(in, true),

    xSeal = subCtx ⇒ {
      ctx.attach(subCtx)
      configureFrom(ctx)
      in.xSeal(ctx)
      if (cancelled) {
        ctx.registerForXStart(this)
        awaitingXStart(in)
      } else running(in)
    },

    xEvent = {
      case DoOnSubscribe => { out.onSubscribe(); stay() }
      case EnableSubscriptionTimeout => stay() // ignore
      case RunContext.SubscriptionTimeout => stay() // ignore
    })

  def awaitingXStart(in: Inport) = state(
    xStart = () => stopCancel(in),

    xEvent = {
      case EnableSubscriptionTimeout => stay() // ignore
      case RunContext.SubscriptionTimeout => stay() // ignore
    })

  def running(in: Inport) = state(
    intercept = false,

    request = (n, _) ⇒ {
      in.request(n.toLong)
      stay()
    },

    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      out.onNext(elem)
      stay()
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out),

    xEvent = {
      case EnableSubscriptionTimeout => stay() // ignore
      case RunContext.SubscriptionTimeout => stay() // ignore
    })
}

private[core] object SubDrainStage {

  case object EnableSubscriptionTimeout
  private case object DoOnSubscribe
}

