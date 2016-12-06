/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.impl.stages.drain.SubDrainStage
import swave.core.impl.stages.InOutStage
import swave.core.impl.{Inport, Outport, RunSupport}
import swave.core.{Spout, Stage}
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImplementation(fullInterceptions = true)
private[core] final class RecoverWithStage(maxRecoveries: Long, pf: PartialFunction[Throwable, Spout[AnyRef]])
  extends InOutStage with RunSupport.RunContextAccess {

  import RecoverWithStage._

  requireArg(maxRecoveries >= 0, "`maxRecoveries` must be >= 0")

  def kind = Stage.Kind.InOut.RecoverWith(maxRecoveries, pf)

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForRunContextAccess(this)
    active(in, out, 0L, maxRecoveries)
  }

  def active(in: Inport, out: Outport, remaining: Long, recoveriesLeft: Long): State = state(
    request = (n, _) => {
      in.request(n.toLong)
      active(in, out, remaining ⊹ n, recoveriesLeft)
    },

    cancel = stopCancelF(in),

    onNext = (elem, from) => {
      requireState(from eq in)
      out.onNext(elem)
      active(in, out, remaining - 1, recoveriesLeft)
    },

    onComplete = from => {
      requireState(from eq in)
      stopComplete(out)
    },

    onError = (e, from) => {
      requireState(from eq in)
      if (recoveriesLeft > 0) {
        var funError: Throwable = null
        val spout =
          try pf.asInstanceOf[PartialFunction[Throwable, AnyRef]].applyOrElse(e, YieldNull)
          catch { case NonFatal(e) => { funError = e; null } }
        if (funError eq null) {
          if (spout ne null) {
            val sub = new SubDrainStage(runContext, this)
            spout.asInstanceOf[Spout[_]].inport.subscribe()(sub)
            awaitingSubOnSubscribe(sub, out, remaining, recoveriesLeft - 1)
          } else stopError(e, out)
        } else stopError(funError, out)
      } else stopError(e, out)
    })

  def awaitingSubOnSubscribe(sub: SubDrainStage, out: Outport, remaining: Long, recoveriesLeft: Long): State = state(
    onSubscribe = from ⇒ {
      requireState(from eq sub)
      sub.sealAndStart()
      if (remaining > 0) sub.request(remaining)
      active(sub, out, remaining, recoveriesLeft)
    },

    request = (n, _) => awaitingSubOnSubscribe(sub, out, remaining ⊹ n, recoveriesLeft),
    cancel = stopCancelF(sub))
}

private[core] object RecoverWithStage {

  private val YieldNull: AnyRef => AnyRef = _ => null
}