/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.spout

import scala.util.control.NonFatal
import swave.core.impl.stages.drain.SubDrainStage
import swave.core.impl.{Inport, Outport, RunSupport}
import swave.core.macros.StageImplementation
import swave.core.util._
import swave.core._
import swave.core.impl.stages.SpoutStage

// format: OFF
@StageImplementation
private[core] final class LazyStartSpoutStage(onStart: () => Spout[AnyRef])
  extends SpoutStage with RunSupport.RunContextAccess {

  def kind = Stage.Kind.Spout.LazyStart(onStart)

  connectOutAndSealWith { (ctx, out) ⇒
    ctx.registerForXStart(this)
    ctx.registerForRunContextAccess(this)
    awaitingXStart(out)
  }

  def awaitingXStart(out: Outport) = state(
    xStart = () => {
      var funError: Throwable = null
      val inport = try onStart().inport catch { case NonFatal(e) => { funError = e; null } }
      if (funError eq null) {
        val sub = new SubDrainStage(runContext, this)
        inport.subscribe()(sub)
        awaitingOnSubscribe(sub, out, 0L)
      } else stopError(funError, out)
    })

  def awaitingOnSubscribe(in: Inport, out: Outport, requested: Long): State = state(
    request = (n, _) => {
      if (requested < 0) stay()
      else awaitingOnSubscribe(in, out, requested ⊹ n)
    },

    cancel = _ => awaitingOnSubscribe(in, out, -1),

    onSubscribe = _ => {
      var funError: Throwable = null
      try RunSupport.sealAndStartSubStream(in.stageImpl, runContext)
      catch { case NonFatal(e) => funError = e }
      if (funError eq null) {
        if (requested != 0) {
          if (requested > 0) {
            in.request(requested)
            running(in, out)
          } else stopCancel(in)
        } else running(in, out)
      } else stopError(funError, out)
    })

  def running(in: Inport, out: Outport) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),
    onNext = onNextF(out),
    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
