/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.PipeElem
import swave.core.impl.{Inport, Outport}
import swave.core.macros.StageImpl
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class ConflateStage(lift: AnyRef => AnyRef, aggregate: (AnyRef, AnyRef) => AnyRef)
  extends InOutStage with PipeElem.InOut.Conflate {

  def pipeElemType: String = "conflate"
  def pipeElemParams: List[Any] = lift :: aggregate :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForXStart(this)
    running(in, out)
  }

  def running(in: Inport, out: Outport) = {

    def awaitingXStart() = state(
      xStart = () => {
        in.request(Long.MaxValue)
        forwarding(0)
      })

    /**
      * Forwarding elements from upstream to downstream as long as there is demand from downstream.
      *
      * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def forwarding(remaining: Long): State = state(
      request = (n, _) ⇒ forwarding(remaining ⊹ n),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        var funError: Throwable = null
        val lifted = try lift(elem) catch { case NonFatal(e) => { funError = e; null } }
        if (funError eq null) {
          if (remaining > 0) {
            out.onNext(lifted)
            forwarding(remaining - 1)
          } else conflating(lifted)
        } else {
          in.cancel()
          stopError(funError, out)
        }
      },

      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    /**
      * No demand from downstream. Aggregating elements from upstream.
      *
      * @param acc the current accumulator value
      */
    def conflating(acc: AnyRef): State = state(
      request = (n, _) ⇒ {
        out.onNext(acc)
        forwarding(n.toLong - 1)
      },

      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        try conflating(aggregate(acc, elem))
        catch { case NonFatal(e) => { in.cancel(); stopError(e, out) } }
      },

      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    awaitingXStart()
  }
}
