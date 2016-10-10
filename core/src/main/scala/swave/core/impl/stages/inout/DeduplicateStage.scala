/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import swave.core.PipeElem
import swave.core.impl.{Inport, Outport}
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class DeduplicateStage extends InOutStage with PipeElem.InOut.Deduplicate {

  def pipeElemType: String = "deduplicate"
  def pipeElemParams: List[Any] = Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out, this) }

  def running(in: Inport, out: Outport, last: AnyRef): State = state(
    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      if (elem != last) {
        out.onNext(elem)
        running(in, out, elem)
      } else {
        in.request(1)
        stay()
      }
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
