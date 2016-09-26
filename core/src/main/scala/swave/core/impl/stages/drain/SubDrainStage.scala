/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.drain

import scala.util.control.NonFatal
import swave.core.impl.stages.Stage
import swave.core.impl.{ Inport, RunContext }
import swave.core.macros.StageImpl
import swave.core.{ SubscriptionTimeoutException, Cancellable, PipeElem }

// format: OFF
@StageImpl(fullInterceptions = true)
private[core] final class SubDrainStage(ctx: RunContext, val out: Stage) extends DrainStage
  with PipeElem.Drain.Sub {

  import SubDrainStage._

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
        val t = ctx.scheduleSubscriptionTimeout(this, ctx.env.settings.subscriptionTimeout)
        awaitingOnSubscribe(cancelled, t)
      case RunContext.SubscriptionTimeout =>
        val msg = s"Subscription attempt from SubDrainStage timed out after ${ctx.env.settings.subscriptionTimeout}"
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

private[core] object SubDrainStage {

  case object EnableSubscriptionTimeout
  private case object DoOnSubscribe
}
