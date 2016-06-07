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
import swave.core.{ Overflow, PipeElem }
import swave.core.impl.{ Inport, Outport }
import swave.core.macros.StageImpl
import swave.core.util.{ RingBuffer, _ }

// format: OFF
@StageImpl
private[core] final class BufferDroppingStage(size: Int, overflowStrategy: Overflow) extends InOutStage
  with PipeElem.InOut.BufferDropping {

  requireArg(size > 0)

  def pipeElemType: String = "bufferDropping"
  def pipeElemParams: List[Any] = size :: overflowStrategy :: Nil

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
      in.request(Long.MaxValue)
      running(in, out, 0)
    })

  /**
   * Upstream and downstream active.
   *
   * @param in        the active upstream
   * @param out       the active downstream
   * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
   */
  def running(in: Inport, out: Outport, remaining: Long): State = {

    @tailrec def handleDemand(rem: Long): State =
      if (rem > 0 && buffer.nonEmpty) {
        out.onNext(buffer.unsafeRead())
        handleDemand(rem - 1)
      } else running(in, out, rem)

    state(
      request = (n, _) ⇒ handleDemand(remaining ⊹ n),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        if (buffer.canWrite) {
          buffer.write(elem)
          handleDemand(remaining)
        } else overflowStrategy.id match {
          case 1 /* Overflow.DropHead */ => { buffer.unsafeDropHead(); stay() }
          case 2 /* Overflow.DropTail */ => { buffer.unsafeDropTail(); stay() }
          case 3 /* Overflow.DropBuffer */ => { buffer.softClear(); stay() }
          case 4 /* Overflow.DropNew */ => stay()
          case 5 /* Overflow.Fail */ => stopError(Overflow.OverflowFailure, out)
        }
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

