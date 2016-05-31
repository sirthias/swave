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
private[core] final class GroupedToCellArray(groupSize: Int, wheelSize: Int, emitSingleEmpty: Boolean) extends InOutStage
  with PipeElem.InOut.GroupedToCellArray {

  require(groupSize > 0)
  require(wheelSize == -1 || isPowerOf2(wheelSize))

  private[this] var wheel: Array[CellArray[AnyRef]] = _
  private[this] var wheelMask: Int = wheelSize - 1

  def pipeElemType: String = "groupedToCellArray"
  def pipeElemParams: List[Any] = groupSize :: (wheelMask + 1) :: emitSingleEmpty :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    val ws = if (wheelSize == -1) ctx.env.settings.maxBatchSize else wheelSize
    wheel = Array.fill(ws)(new CellArray(groupSize))
    wheelMask = ws - 1
    running(in, out)
  }

  def running(in: Inport, out: Outport): State = {

    /**
     * Waiting for a request from downstream.
     */
    def awaitingDemand() = state(
      request = (n, _) ⇒ {
        in.request(groupSize.toLong)
        collecting(wheelIx = 0, groupCursor = 0, remaining = n.toLong, firstElem = true)
      },

      cancel = stopCancelF(in),
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    /**
     * Gathering up the elements for the next group.
     *
     * @param wheelIx     lower bits index into the wheel yielding the current group (i.e. CellArray instance)
     * @param groupCursor index of the next element in the current group, < groupSize
     * @param remaining   number of elements already requested by downstream but not yet delivered, > 0
     * @param firstElem   true if we are still awaiting the very first element from upstream
     */
    def collecting(wheelIx: Int, groupCursor: Int, remaining: Long, firstElem: Boolean): State = state(
      request = (n, _) ⇒ collecting(wheelIx, groupCursor, remaining ⊹ n, firstElem),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        val cellArray = wheel(wheelIx & wheelMask)
        cellArray(groupCursor) = elem
        val gcPlus1 = groupCursor + 1
        if (gcPlus1 == groupSize) {
          out.onNext(cellArray)
          if (remaining > 1) {
            in.request(groupSize.toLong)
            collecting(wheelIx + 1, groupCursor = 0, remaining - 1, firstElem = false)
          } else awaitingDemand()
        } else collecting(wheelIx, gcPlus1, remaining, firstElem = false)
      },

      onComplete = _ ⇒ {
        if (groupCursor > 0 || firstElem && emitSingleEmpty)
          out.onNext(wheel(wheelIx & wheelMask).copyOf(length = groupCursor))
        stopComplete(out)
      },

      onError = stopErrorF(out))

    awaitingDemand()
  }
}

