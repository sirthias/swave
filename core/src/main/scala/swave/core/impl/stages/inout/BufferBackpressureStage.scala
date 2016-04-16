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
import swave.core.PipeElem
import swave.core.impl.{ Outport, Inport }
import swave.core.util.RingBuffer
import swave.core.util._

// format: OFF
private[core] final class BufferBackpressureStage(size: Int) extends InOutStage
  with PipeElem.InOut.BufferWithBackpressure {

  require(size > 0)

  def pipeElemType: String = "bufferBackpressure"
  def pipeElemParams: List[Any] = size :: Nil

  private[this] val buffer = new RingBuffer[AnyRef](roundUpToNextPowerOf2(size))

  connectInOutAndStartWith { (ctx, in, out) ⇒
    in.request(size.toLong)
    running(in, out, size.toLong, 0)
  }

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

    @tailrec def handleDemand(pending: Long, remaining: Long): State =
      if (remaining > 0 && buffer.nonEmpty) {
        out.onNext(buffer.unsafeRead())
        handleDemand(pending, remaining - 1)
      } else {
        val remainingPlus = remaining ⊹ buffer.available
        if (pending < remainingPlus) in.request(remainingPlus - pending)
        running(in, out, remainingPlus, remaining)
      }

    state(name = "running",
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
  def draining(out: Outport) =
    state(name = "draining",

      request = (n, _) ⇒ {
        @tailrec def rec(n: Int): State =
          if (buffer.nonEmpty) {
            if (n > 0) {
              out.onNext(buffer.unsafeRead())
              rec(n - 1)
            } else stay()
          } else stopComplete(out)
        rec(n)
      },

      cancel = stopF)
}

