/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import swave.core.impl.stages.InOutStage

import scala.annotation.tailrec
import swave.core.{Buffer, BufferOverflowFailure, Stage}
import swave.core.impl.{Inport, Outport}
import swave.core.impl.util.RingBuffer
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImplementation
private[core] final class BufferDroppingStage(size: Int, overflowStrategy: Buffer.OverflowStrategy) extends InOutStage {

  requireArg(size > 0, "`size` must be > 0")

  def kind = Stage.Kind.InOut.BufferDropping(size, overflowStrategy)

  private[this] val buffer = new RingBuffer[AnyRef](roundUpToPowerOf2(size))

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
          case 3 /* Overflow.DropBuffer */ => { buffer.clear(); stay() }
          case 4 /* Overflow.DropNew */ => stay()
          case 5 /* Overflow.Fail */ => stopError(new BufferOverflowFailure(elem), out)
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
