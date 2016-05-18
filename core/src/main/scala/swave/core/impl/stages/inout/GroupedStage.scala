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

import scala.collection.mutable
import swave.core.macros.StageImpl
import swave.core.impl.{ Outport, Inport }
import swave.core.PipeElem
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class GroupedStage(groupSize: Int, emitSingleEmpty: Boolean, builder: mutable.Builder[Any, AnyRef]) extends InOutStage
  with PipeElem.InOut.Drop {

  require(groupSize > 0)

  def pipeElemType: String = "group"
  def pipeElemParams: List[Any] = groupSize :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport): State = {

    /**
     * Waiting for a request from downstream.
     */
    def awaitingDemand() = state(
      request = (n, _) ⇒ {
        in.request(groupSize.toLong)
        collecting(groupSize, n.toLong, firstElem = true)
      },

      cancel = stopCancelF(in),
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    /**
     * Gathering up the elements for the next group.
     *
     * @param pending   number of elements still required for completing the current group,
     *                  already requested from upstream but not yet received, > 0
     * @param remaining number of elements already requested by downstream but not yet delivered, > 0
     * @param firstElem true if we are still awaiting the very first element from upstream
     */
    def collecting(pending: Int, remaining: Long, firstElem: Boolean): State = state(
      request = (n, _) ⇒ collecting(pending, remaining ⊹ n, firstElem),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        builder += elem
        if (pending == 1) {
          val group = builder.result()
          builder.clear()
          out.onNext(group)
          if (remaining > 1) {
            in.request(groupSize.toLong)
            collecting(groupSize, remaining - 1, firstElem = false)
          } else awaitingDemand()
        } else collecting(pending - 1, remaining, firstElem = false)
      },

      onComplete = _ ⇒ {
        if (pending < groupSize || firstElem && emitSingleEmpty)
          out.onNext(builder.result())
        builder.clear() // don't hold on to elements
        stopComplete(out)
      },

      onError = stopErrorF(out))

    awaitingDemand()
  }
}

