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
private[core] final class DropStage(count: Long) extends InOutStage with PipeElem.InOut.Drop {

  require(count > 0)

  def pipeElemType: String = "drop"
  def pipeElemParams: List[Any] = count :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForXStart(this)
    awaitingXStart(in, out)
  }

  /**
   * @param in  the active upstream
   * @param out the active downstream
   */
  def awaitingXStart(in: Inport, out: Outport) = state(
    xStart = () => {
      in.request(count)
      running(in, out)
    })

  def running(in: Inport, out: Outport) = {

    /**
     * Waiting for elements from upstream to drop.
     *
     * @param remaining number of elems still to drop, >0
     */
    def dropping(remaining: Long): State = state(
      request = (n, _) ⇒ {
        in.request(n.toLong)
        stay()
      },

      cancel = stopCancelF(in),
      onNext = (_, _) ⇒ if (remaining > 1) dropping(remaining - 1) else draining(),
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    /**
     * Simply forwarding elements from upstream to downstream.
     */
    def draining() = state(
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

    dropping(count)
  }
}

