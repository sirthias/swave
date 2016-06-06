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

import scala.annotation.tailrec
import swave.core.macros.StageImpl
import swave.core.PipeElem
import swave.core.impl.{ Outport, Inport }
import swave.core.util.RingBuffer
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class BufferBackpressureStage(size: Int) extends InOutStage
  with PipeElem.InOut.BufferWithBackpressure {

  requireArg(size > 0)

  def pipeElemType: String = "bufferBackpressure"
  def pipeElemParams: List[Any] = size :: Nil

  private[this] val buffer = new RingBuffer[AnyRef](roundUpToNextPowerOf2(size))

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
      in.request(size.toLong)
      running(in, out, size.toLong, 0)
    })

  /**
   * Upstream and downstream active.
   * We always have `buffer.available` elements pending from upstream,
   * i.e. we are trying to always have the buffer filled.
   *
   * @param in        the active upstream
   * @param out       the active downstream
   * @param pending   number of elements already requested from upstream but not yet received, >= 0
   * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
   */
  def running(in: Inport, out: Outport, pending: Long, remaining: Long): State = {

    @tailrec def handleDemand(pend: Long, rem: Long): State =
      if (rem > 0 && buffer.nonEmpty) {
        out.onNext(buffer.unsafeRead())
        handleDemand(pend, rem - 1)
      } else {
        val remainingPlus = rem ⊹ buffer.available
        if (pend < remainingPlus) in.request(remainingPlus - pend)
        running(in, out, remainingPlus, rem)
      }

    state(
      request = (n, _) ⇒ handleDemand(pending, remaining ⊹ n),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        requireState(buffer.canWrite)
        buffer.write(elem)
        handleDemand(pending - 1, remaining)
      },

      onComplete = _ ⇒ {
        if (remaining > 0) {
          requireState(buffer.isEmpty)
          stopComplete(out)
        } else {
          if (buffer.isEmpty) stopComplete(out) else draining(out)
        }
      },

      onError = stopErrorF(out))
  }

  /**
   * Upstream completed, downstream active and buffer non-empty.
   *
   * @param out the active downstream
   */
  def draining(out: Outport) = state(
    request = (n, _) ⇒ {
      @tailrec def rec(nn: Int): State =
        if (buffer.nonEmpty) {
          if (nn > 0) {
            out.onNext(buffer.unsafeRead())
            rec(nn - 1)
          } else stay()
        } else stopComplete(out)
      rec(n)
    },

    cancel = stopF)
}

