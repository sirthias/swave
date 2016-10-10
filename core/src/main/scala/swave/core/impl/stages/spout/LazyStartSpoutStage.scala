/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.spout

import scala.util.control.NonFatal
import swave.core.impl.stages.drain.SubDrainStage
import swave.core.impl.{Inport, Outport, RunContext}
import swave.core.macros.StageImpl
import swave.core.util._
import swave.core._

// format: OFF
@StageImpl
private[core] final class LazyStartSpoutStage(onStart: () => Spout[AnyRef])
  extends SpoutStage with PipeElem.Spout.Lazy {

  def pipeElemType: String = "Spout.lazyStart"
  def pipeElemParams: List[Any] = onStart :: Nil

  connectOutAndSealWith { (ctx, out) ⇒
    ctx.registerForXStart(this)
    awaitingXStart(ctx, out)
  }

  def awaitingXStart(ctx: RunContext, out: Outport) = state(
    xStart = () => {
      var funError: Throwable = null
      val inport = try onStart().inport catch { case NonFatal(e) => { funError = e; null } }
      if (funError eq null) {
        val sub = new SubDrainStage(ctx, this)
        inport.subscribe()(sub)
        awaitingOnSubscribe(ctx, sub, out, 0L)
      } else stopError(funError, out)
    })

  def awaitingOnSubscribe(ctx: RunContext, in: Inport, out: Outport, requested: Long): State = state(
    request = (n, _) => {
      if (requested < 0) stay()
      else awaitingOnSubscribe(ctx, in, out, requested ⊹ n)
    },

    cancel = _ => awaitingOnSubscribe(ctx, in, out, -1),

    onSubscribe = _ => {
      ctx.sealAndStartSubStream(in)
      if (requested != 0) {
        if (requested > 0) {
          in.request(requested)
          running(in, out)
        } else stopCancel(in)
      } else running(in, out)
    })

  def running(in: Inport, out: Outport) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),
    onNext = onNextF(out),
    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
