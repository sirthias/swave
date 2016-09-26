/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.impl.stages.drain.SubDrainStage
import swave.core.impl.{ Inport, Outport, RunContext }
import swave.core.{ PipeElem, Spout }
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImpl(fullInterceptions = true)
private[core] final class RecoverWithStage(maxRecoveries: Long, pf: PartialFunction[Throwable, Spout[AnyRef]])
  extends InOutStage with PipeElem.InOut.RecoverWith {

  import RecoverWithStage._

  requireArg(maxRecoveries >= 0)

  def pipeElemType: String = "recoverWith"
  def pipeElemParams: List[Any] = maxRecoveries :: pf :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    active(ctx, in, out, 0L, maxRecoveries)
  }

  def active(ctx: RunContext, in: Inport, out: Outport, remaining: Long, recoveriesLeft: Long): State = state(
    request = (n, _) => {
      in.request(n.toLong)
      active(ctx, in, out, remaining ⊹ n, recoveriesLeft)
    },

    cancel = stopCancelF(in),

    onNext = (elem, from) => {
      requireState(from eq in)
      out.onNext(elem)
      active(ctx, in, out, remaining - 1, recoveriesLeft)
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
            val sub = new SubDrainStage(ctx, this)
            spout.asInstanceOf[Spout[AnyRef]].inport.subscribe()(sub)
            awaitingSubOnSubscribe(ctx, sub, out, remaining, recoveriesLeft - 1)
          } else stopError(e, out)
        } else stopError(funError, out)
      } else stopError(e, out)
    })

  def awaitingSubOnSubscribe(ctx: RunContext, sub: SubDrainStage, out: Outport, remaining: Long,
                             recoveriesLeft: Long): State = state(
    onSubscribe = from ⇒ {
      requireState(from eq sub)
      sub.sealAndStart()
      if (remaining > 0) sub.request(remaining)
      active(ctx, sub, out, remaining, recoveriesLeft)
    },

    request = (n, _) => awaitingSubOnSubscribe(ctx, sub, out, remaining ⊹ n, recoveriesLeft),
    cancel = stopCancelF(sub))
}

private[core] object RecoverWithStage {

  private val YieldNull: AnyRef => AnyRef = _ => null
}