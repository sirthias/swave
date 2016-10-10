/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.PipeElem
import swave.core.impl.{Inport, Outport}
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class CollectStage(pf: PartialFunction[AnyRef, AnyRef]) extends InOutStage with PipeElem.InOut.Collect {

  def pipeElemType: String = "collect"
  def pipeElemParams: List[Any] = pf :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    val mismatchF: AnyRef => this.type = { elem =>
      in.request(1)
      this // we use `this` as a special result instance signalling the mismatch of a single collect
    }
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
        stay()
      } else {
        in.cancel()
        stopError(funError, out)
      }
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
