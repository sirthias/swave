/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.spout

import scala.annotation.tailrec
import swave.core.PipeElem
import swave.core.impl.Outport
import swave.core.macros.StageImpl
import swave.core.util.RingBuffer

// format: OFF
@StageImpl
private[core] final class RingBufferSpoutStage(buffer: RingBuffer[AnyRef])
  extends SpoutStage with PipeElem.Spout.RingBuffer {

  def pipeElemType: String = "Spout.fromRingBuffer"
  def pipeElemParams: List[Any] = buffer :: Nil

  connectOutAndSealWith { (ctx, out) ⇒
    if (buffer.isEmpty) {
      ctx.registerForXStart(this)
      awaitingXStart(out)
    } else running(out)
  }

  def awaitingXStart(out: Outport) = state(
    xStart = () => stopComplete(out))

  def running(out: Outport) = state(
    request = (n, _) ⇒ {
      @tailrec def rec(n: Int): State = {
        out.onNext(buffer.unsafeRead())
        if (buffer.nonEmpty) {
          if (n > 1) rec(n - 1)
          else stay()
        } else stopComplete(out)
      }

      rec(n)
    },

    cancel = stopF)
}
