/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.Stage
import swave.core.impl.stages.InOutStage
import swave.core.impl.{Inport, Outport}
import swave.core.macros.StageImplementation

// format: OFF
@StageImplementation
private[core] final class CollectStage(pf: PartialFunction[AnyRef, AnyRef]) extends InOutStage {

  def kind = Stage.Kind.InOut.Collect(pf)

  connectInOutAndSealWith { (in, out) ⇒
    val mismatchF: AnyRef => this.type =
      _ => this // we use `this` as a special result instance signalling the mismatch of a single collect
    running(in, out, mismatchF)
  }

  def running(in: Inport, out: Outport, mismatchFun: AnyRef => this.type) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      var funError: Throwable = null
      val collected = try pf.applyOrElse(elem, mismatchFun) catch { case NonFatal(e) => { funError = e; null } }
      if (funError eq null) {
        if (collected ne this) out.onNext(collected)
        else in.request(1)
        stay()
      } else {
        in.cancel()
        stopError(funError, out)
      }
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
