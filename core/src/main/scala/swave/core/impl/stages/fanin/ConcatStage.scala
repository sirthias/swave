/*
 * Copyright © 2016 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swave.core.impl.stages.fanin

import swave.core.PipeElem
import swave.core.impl.{ InportList, Outport }
import swave.core.macros.StageImpl
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class ConcatStage(subs: InportList) extends FanInStage with PipeElem.FanIn.Concat {

  require(subs.nonEmpty)

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

