/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.source

import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import swave.core.impl.stages.drain.SubDrainStage
import swave.core.impl.stages.SignalStashing
import swave.core.impl.{ RunContext, Inport, Outport }
import swave.core.macros.StageImpl
import swave.core.util._
import swave.core._

// format: OFF
@StageImpl
private[core] final class LazyStartSourceStage(onStart: () => Stream[AnyRef], timeout: Duration)
  extends SourceStage with SignalStashing with PipeElem.Source.Lazy {

  def pipeElemType: String = "Stream.lazyStart"
  def pipeElemParams: List[Any] = onStart :: timeout :: Nil

  connectOutAndSealWith { (ctx, out) ⇒
    configureStash(ctx)
    ctx.registerForXStart(this)
    awaitingXStart(ctx, out)
  }

  def awaitingXStart(ctx: RunContext, out: Outport) = state(
    xStart = () => {
      try {
        val inport = onStart().inport
        val sub = new SubDrainStage(ctx, this, timeout orElse ctx.env.settings.subscriptionTimeout)
        inport.subscribe()(sub)
        awaitingOnSubscribe(ctx, sub, out)
      } catch {
        case NonFatal(e) => stopError(e, out)
      }
    })

  def awaitingOnSubscribe(ctx: RunContext, in: Inport, out: Outport) = state(
    request = stashRequest,
    cancel = stashCancel,

    onSubscribe = _ => {
      ctx.sealAndStartSubStream(in)
      unstashAll()
      running(in, out)
    })

  def running(in: Inport, out: Outport) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),
    onNext = onNextF(out),
    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
