/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.spout

import scala.annotation.tailrec
import scala.util.control.NonFatal
import swave.core.Stage
import swave.core.impl.Outport
import swave.core.macros.StageImplementation
import swave.core.Spout.Unfolding
import swave.core.impl.stages.SpoutStage

// format: OFF
@StageImplementation
private[core] final class UnfoldSpoutStage(zero: AnyRef, f: AnyRef => Unfolding[AnyRef, AnyRef]) extends SpoutStage {

  def kind = Stage.Kind.Spout.Unfold(zero, f)

  connectOutAndSealWith { (ctx, out) ⇒ running(out, zero) }

  def running(out: Outport, s: AnyRef): State = state(
    request = (n, _) ⇒ {
      @tailrec def rec(n: Int, s: AnyRef): State =
        if (n > 0) {
          var funError: Throwable = null
          val unfolding = try f(s) catch { case NonFatal(e) => { funError = e; null } }
          if (funError eq null) {
            unfolding match {
              case Unfolding.Emit(elem, next) =>
                out.onNext(elem)
                rec(n - 1, next)
              case Unfolding.EmitFinal(elem) =>
                out.onNext(elem)
                stopComplete(out)
              case Unfolding.Complete =>
                stopComplete(out)
            }
          } else stopError(funError, out)
        } else running(out, s)
      rec(n, s)
    },

    cancel = stopF)
}
