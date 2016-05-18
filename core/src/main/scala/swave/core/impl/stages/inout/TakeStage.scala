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
import swave.core.macros.StageImpl
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class TakeStage(count: Long) extends InOutStage with PipeElem.InOut.Take {

  require(count >= 0)

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

