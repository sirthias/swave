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
private[core] final class FoldStage(zero: AnyRef, f: (Any, Any) ⇒ AnyRef) extends InOutStage {

  def kind = Stage.Kind.InOut.Fold(zero, f)

  connectInOutAndSealWith { (in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport) = {

    /**
      * Waiting for a request from downstream.
      */
    def awaitingDemand() = state(
      request = (_, _) ⇒ {
        in.request(Long.MaxValue)
        folding(zero)
      },

      cancel = stopCancelF(in),
      onComplete = _ => awaitingDemandUpstreamGone(),
      onError = stopErrorF(out))

    /**
      * Upstream completed, awaiting demand from downstream.
      */
    def awaitingDemandUpstreamGone() = state(
      request = (_, _) ⇒ {
        out.onNext(zero)
        stopComplete(out)
      },

      cancel = stopF)

    /**
      * Applying the fold function to all incoming elements.
      *
      * @param acc the current fold state
      */
    def folding(acc: AnyRef): State = state(
      request = (_, _) ⇒ stay(),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        try folding(f(acc, elem))
        catch { case NonFatal(e) => { in.cancel(); stopError(e, out) } }
      },

      onComplete = _ ⇒ {
        out.onNext(acc)
        stopComplete(out)
      },

      onError = stopErrorF(out))

    awaitingDemand()
  }
}
