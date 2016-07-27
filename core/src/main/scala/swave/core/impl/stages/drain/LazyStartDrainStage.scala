/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.drain

import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import swave.core.impl.stages.spout.SubSpoutStage
import swave.core.impl.{ Inport, Outport, RunContext }
import swave.core.macros.StageImpl
import swave.core.util._
import swave.core._

// format: OFF
@StageImpl
private[core] final class LazyStartDrainStage(onStart: () => Drain[AnyRef, AnyRef], timeout: Duration,
                                              connectResult: AnyRef => Unit) extends DrainStage with PipeElem.Drain.Lazy {

  def pipeElemType: String = "Drain.lazyStart"
  def pipeElemParams: List[Any] = onStart :: timeout :: Nil

  connectInAndSealWith { (ctx, in) ⇒
    ctx.registerForXStart(this)
    awaitingXStart(ctx, in)
  }

  def awaitingXStart(ctx: RunContext, in: Inport) = state(
    xStart = () => {
      var funError: Throwable = null
      val innerDrain =
        try {
          val d = onStart()
          connectResult(d.result)
          d
        } catch { case NonFatal(e) => { funError = e; null } }
      if (funError eq null) {
        val sub = new SubSpoutStage(ctx, this, timeout orElse ctx.env.settings.subscriptionTimeout)
        sub.subscribe()(innerDrain.outport)
        ctx.sealAndStartSubStream(sub)
        running(in, sub)
      } else {
        in.cancel()
        stop(funError)
      }
    })

  def running(in: Inport, out: Outport) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),
    onNext = onNextF(out),
    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
