/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.spout

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
private[core] final class LazyStartSpoutStage(onStart: () => Spout[AnyRef], timeout: Duration)
  extends SpoutStage with SignalStashing with PipeElem.Source.Lazy {

  def pipeElemType: String = "Spout.lazyStart"
  def pipeElemParams: List[Any] = onStart :: timeout :: Nil

  connectOutAndSealWith { (ctx, out) ⇒
    configureStash(ctx)
    ctx.registerForXStart(this)
    awaitingXStart(ctx, out)
  }

  def awaitingXStart(ctx: RunContext, out: Outport) = state(
    xStart = () => {
      var funError: Throwable = null
      val inport = try onStart().inport catch { case NonFatal(e) => { funError = e; null } }
      if (funError eq null) {
        val sub = new SubDrainStage(ctx, this, timeout orElse ctx.env.settings.subscriptionTimeout)
        inport.subscribe()(sub)
        awaitingOnSubscribe(ctx, sub, out)
      } else stopError(funError, out)
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
