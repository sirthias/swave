/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import swave.core.Stage
import swave.core.impl.stages.InOutStage
import swave.core.impl.util.RingBuffer
import swave.core.impl.{Inport, Outport}
import swave.core.macros.StageImplementation
import swave.core.util._

// format: OFF
@StageImplementation
private[core] final class DropLastStage(count: Int) extends InOutStage {

  def kind = Stage.Kind.InOut.DropLast(count)

  private[this] val buffer = new RingBuffer[AnyRef](roundUpToPowerOf2(count))

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForXStart(this)
    awaitingXStart(in, out)
  }

  def awaitingXStart(in: Inport, out: Outport) = state(
    xStart = () => {
      in.request(count.toLong)
      running(in, out)
    })

  def running(in: Inport, out: Outport): State = state(
    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      if (buffer.count == count) out.onNext(buffer.unsafeRead())
      buffer.write(elem)
      stay()
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
