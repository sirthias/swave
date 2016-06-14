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

package swave.core.impl.stages.inout

import swave.core.PipeElem
import swave.core.impl.{ Inport, Outport }
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

