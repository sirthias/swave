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
        val lifted = lift(elem)
        if (remaining > 0) {
          out.onNext(lifted)
          forwarding(remaining - 1)
        } else conflating(lifted)
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
      onNext = (elem, _) ⇒ conflating(aggregate(acc, elem)),
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    awaitingXStart()
  }
}

