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

import swave.core.{ Errors, PipeElem }
import swave.core.impl.{ Outport, Inport }
import swave.core.util._

// format: OFF
private[core] final class LimitStage(max: Long, cost: AnyRef ⇒ Long) extends InOutStage with PipeElem.InOut.Limit {

  require(max >= 0)

  def pipeElemType: String = "limit"
  def pipeElemParams: List[Any] = max :: cost :: Nil

  connectInOutAndStartWith { (ctx, in, out) ⇒ running(in, out, max) }

  /**
   * @param in        the active upstream
   * @param out       the active downstream
   * @param remaining max number of elements still allowed before completion, >= 0
   */
  def running(in: Inport, out: Outport, remaining: Long): State =
    state(name = "running",

      request = (n, _) ⇒ {
        in.request(n.toLong)
        stay()
      },

      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        val rem = remaining - cost(elem)
        if (rem >= 0) {
          out.onNext(elem)
          running(in, out, rem)
        } else {
          in.cancel()
          stopError(new Errors.StreamLimitExceeded(max, elem), out)
        }
      },

      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))
}

