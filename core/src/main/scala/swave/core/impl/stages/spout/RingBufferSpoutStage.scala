/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.spout

import scala.annotation.tailrec
import swave.core.Stage
import swave.core.impl.Outport
import swave.core.impl.stages.SpoutStage
import swave.core.impl.util.RingBuffer
import swave.core.macros.StageImplementation

// format: OFF
@StageImplementation
private[core] final class RingBufferSpoutStage(buffer: RingBuffer[AnyRef]) extends SpoutStage {

  def kind = Stage.Kind.Spout.FromRingBuffer(buffer)

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
