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

import swave.core.PipeElem
import swave.core.util._
import swave.core.impl.{ InportList, Inport, Outport }

// format: OFF
private[core] final class FirstNonEmptyStage(subs: InportList) extends FanInStage with PipeElem.FanIn.FirstNonEmpty {

  require(subs.nonEmpty)

  def pipeElemType: String = "fanInFirstNonEmpty"
  def pipeElemParams: List[Any] = Nil

  connectFanInAndStartWith(subs) { (ctx, out) ⇒ running(out) }

  def running(out: Outport) = {

    /**
     * Waiting for the first element to arrive from the ins head.
     *
     * @param ins     the active upstreams
     * @param pending the number of elements already requested from the ins head, >= 0
     */
    def awaitingFirstElement(ins: InportList, pending: Long): State =
      state(name = "awaitingFirstElement",

        request = (n, _) ⇒ {
          ins.in.request(n.toLong)
          awaitingFirstElement(ins, pending ⊹ n)
        },

        cancel = stopCancelF(ins),

        onNext = (elem, _) ⇒ {
          out.onNext(elem)
          cancelAll(ins, except = ins.in)
          draining(ins.in)
        },

        onComplete = in ⇒ {
          if (in eq ins.in) {
            val tail = ins.tail
            if (tail.nonEmpty) {
              if (pending > 0) tail.in.request(pending) // retarget demand
              awaitingFirstElement(tail, pending)
            } else stopComplete(out)
          } else awaitingFirstElement(ins remove_! in, pending)
        },

        onError = cancelAllAndStopErrorF(ins, out))

    /**
     * Simply forwarding elements from the given `in` to downstream.
     */
    def draining(in: Inport) =
      state(name = "draining",

        request = (n, _) ⇒ {
          in.request(n.toLong)
          stay()
        },

        cancel = stopCancelF(in),

        onNext = (elem, _) ⇒ {
          out.onNext(elem)
          stay()
        },

        onComplete = from => {
          // ignore completes from other ins that might have dispatched before our cancellation arrived
          if (from eq in) stopComplete(out)
          else stay()
        },

        onError = (e, from) => {
          if (from ne in) in.cancel()
          stopError(e, out)
        })

    awaitingFirstElement(subs, 0)
  }
}

