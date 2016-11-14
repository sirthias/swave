/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.Stage
import swave.core.impl.{Inport, Outport}
import swave.core.macros.StageImplementation

// format: OFF
@StageImplementation
private[core] final class ScanStage(zero: AnyRef, f: (AnyRef, AnyRef) ⇒ AnyRef) extends InOutStage {

  def kind = Stage.Kind.InOut.Scan(zero, f)

  connectInOutAndSealWith { (ctx, in, out) ⇒ awaitingDemand(in, out) }

  /**
    * @param in  the active upstream
    * @param out the active downstream
    */
  def awaitingDemand(in: Inport, out: Outport): State = state(
    request = (n, _) ⇒ {
      out.onNext(zero)
      if (n > 1) in.request((n - 1).toLong)
      running(in, out, zero)
    },

    cancel = stopCancelF(in),
    onComplete = _ ⇒ drainingZero(out),
    onError = stopErrorF(out))

  /**
    * @param in        the active upstream
    * @param out       the active downstream
    * @param last      the last value produced
    */
  def running(in: Inport, out: Outport, last: AnyRef): State = state(
    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      var funError: Throwable = null
      val next = try f(last, elem) catch { case NonFatal(e) => { funError = e; null } }
      if (funError eq null) {
        out.onNext(next)
        running(in, out, next)
      } else {
        in.cancel()
        stopError(funError, out)
      }
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))

  /**
    * Upstream completed without having produced any element, downstream active, awaiting first request.
    *
    * @param out  the active downstream
    */
  def drainingZero(out: Outport) = state(
    request = (_, _) ⇒ {
      out.onNext(zero)
      stopComplete(out)
    },

    cancel = stopF)
}
