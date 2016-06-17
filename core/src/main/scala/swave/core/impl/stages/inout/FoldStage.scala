/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.PipeElem
import swave.core.impl.{ Outport, Inport }
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class FoldStage(zero: AnyRef, f: (AnyRef, AnyRef) ⇒ AnyRef) extends InOutStage
  with PipeElem.InOut.Fold {

  def pipeElemType: String = "fold"
  def pipeElemParams: List[Any] = zero :: f :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out) }

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
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

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
