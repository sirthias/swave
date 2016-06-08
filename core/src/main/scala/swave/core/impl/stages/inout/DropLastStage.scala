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
private[core] final class DropLastStage(count: Int) extends InOutStage with PipeElem.InOut.DropLast {

  def pipeElemType: String = "dropLast"
  def pipeElemParams: List[Any] = count :: Nil

  private[this] val buffer = new RingBuffer[AnyRef](roundUpToNextPowerOf2(count))

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
    request = (n, _) ⇒ {
      in.request(n.toLong)
      stay()
    },

    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      if (buffer.size == count) out.onNext(buffer.unsafeRead())
      buffer.write(elem)
      stay()
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}

