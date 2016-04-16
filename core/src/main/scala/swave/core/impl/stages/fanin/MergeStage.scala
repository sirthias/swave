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

package swave.core.impl.stages.fanin

import scala.annotation.tailrec
import swave.core.PipeElem
import swave.core.impl.{ InportAnyRefList, InportList, Outport }
import swave.core.util._

// format: OFF
private[core] final class MergeStage(subs: InportList, eagerComplete: Boolean)
  extends FanInStage with PipeElem.FanIn.Concat {

  require(subs.nonEmpty)

  def pipeElemType: String = "fanInMerge"
  def pipeElemParams: List[Any] = Nil

  private[this] val buffer: RingBuffer[InportAnyRefList] = new RingBuffer(roundUpToNextPowerOf2(subs.size))

  connectFanInAndStartWith(subs) { (ctx, out) ⇒
    @tailrec def rec(remaining: InportList, result: InportAnyRefList): InportAnyRefList =
      if (remaining.nonEmpty) {
        remaining.in.request(1)
        rec(remaining.tail, result.prepend(remaining.in, null))
      } else result

    val ins = rec(subs, InportAnyRefList.empty)
    running(out, ins, 0)
  }

  /**
   * @param out       the active downstream
   * @param ins       the active upstreams
   * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
   */
  def running(out: Outport, ins: InportAnyRefList, remaining: Long): State =
    state(name = "awaitingDemand",

      request = (n, _) ⇒ {
        @tailrec def rec(n: Int): State =
          if (buffer.nonEmpty) {
            if (n > 0) {
              val record = buffer.unsafeRead()
              out.onNext(record.value)
              record.value = null
              record.in.request(1)
              rec(n - 1)
            } else stay()
          } else running(out, ins, n.toLong)

        if (remaining > 0) running(out, ins, remaining ⊹ n) else rec(n)
      },

      cancel = stopCancelF(ins),

      onNext = (elem, from) ⇒ {
        @tailrec def store(current: InportAnyRefList): State = {
          requireState(current.nonEmpty)
          if (current.in eq from) {
            current.value = elem
            buffer.write(current)
            stay()
          } else store(current.tail)
        }

        if (remaining > 0) {
          out.onNext(elem)
          from.request(1)
          running(out, ins, remaining - 1)
        } else store(ins)
      },

      onComplete = from ⇒ {
        if (eagerComplete || ins.tail.isEmpty) {
          cancelAll(ins, from)
          if (buffer.isEmpty) stopComplete(out) else draining(out)
        } else running(out, ins remove_! from, remaining)
      },

      onError = cancelAllAndStopErrorF(ins, out))

  /**
   * Upstreams completed, downstream active and buffer non-empty.
   *
   * @param out the active downstream
   */
  def draining(out: Outport) =
    state(name = "draining",

      request = (n, _) ⇒ {
        @tailrec def rec(n: Int): State =
          if (buffer.nonEmpty) {
            if (n > 0) {
              out.onNext(buffer.unsafeRead().value)
              rec(n - 1)
            } else stay()
          } else stopComplete(out)
        rec(n)
      },

      cancel = stopF)
}

