/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.fanin

import swave.core.PipeElem
import swave.core.impl.{ InportList, Outport }
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImpl(fullInterceptions = true)
private[core] final class ConcatStage(subs: InportList) extends FanInStage with PipeElem.FanIn.Concat {

  requireArg(subs.nonEmpty)

  def pipeElemType: String = "fanInConcat"
  def pipeElemParams: List[Any] = Nil

  connectFanInAndSealWith(subs) { (ctx, out) ⇒ running(out, subs, 0) }

  /**
   * @param out     the active downstream
   * @param ins     the active upstreams
   * @param pending number of elements requested from upstream but not yet received, >= 0
   */
  def running(out: Outport, ins: InportList, pending: Long): State = state(
    request = (n, _) ⇒ {
      ins.in.request(n.toLong)
      running(out, ins, pending ⊹ n)
    },

    cancel = stopCancelF(ins),

    onNext = (elem, _) ⇒ {
      out.onNext(elem)
      running(out, ins, pending - 1)
    },

    onComplete = in ⇒ {
      if (in eq ins.in) {
        val tail = ins.tail
        if (tail.nonEmpty) {
          if (pending > 0) tail.in.request(pending)
          running(out, tail, pending)
        } else stopComplete(out)
      } else running(out, ins remove_! in, pending)
    },

    onError = cancelAllAndStopErrorF(ins, out))
}
