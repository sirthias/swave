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
import swave.core.impl.{ Outport, Inport }

// format: OFF
private[core] final class NopStage extends InOutStage with PipeElem.InOut.Nop {

  def pipeElemType: String = "nop"
  def pipeElemParams: List[Any] = Nil

  connectInOutAndStartWith { (ctx, in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport) =
    state(name = "running",
      interceptWhileHandling = false,

      request = (n, _) ⇒ {
        in.request(n.toLong)
        stay()
      },

      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        out.onNext(elem)
        stay()
      },

      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))
}

