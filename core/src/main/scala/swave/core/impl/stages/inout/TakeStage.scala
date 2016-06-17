/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import swave.core.PipeElem
import swave.core.impl.{ Outport, Inport }
import swave.core.macros.StageImpl
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class TakeStage(count: Long) extends InOutStage with PipeElem.InOut.Take {

  requireArg(count >= 0)

  def pipeElemType: String = "take"
  def pipeElemParams: List[Any] = count :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    if (count == 0) {
      ctx.registerForXStart(this)
      awaitingXStart(in, out)
    } else running(in, out, count)
  }

  /**
   * @param in  the active upstream
   * @param out the active downstream
   */
  def awaitingXStart(in: Inport, out: Outport) = state(
    xStart = () => {
      in.cancel()
      stopComplete(out)
    })

  /**
   * @param in        the active upstream
   * @param out       the active downstream
   * @param remaining max number of elements still allowed before completion, > 0
   */
  def running(in: Inport, out: Outport, remaining: Long): State = state(
    request = (n, _) ⇒ {
      in.request(math.min(n.toLong, remaining))
      stay()
    },

    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      out.onNext(elem)
      if (remaining == 1) {
        in.cancel()
        stopComplete(out)
      } else running(in, out, remaining - 1)
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
