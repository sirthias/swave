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

package swave.core.impl.stages.fanout

import scala.annotation.tailrec
import swave.core.PipeElem
import swave.core.impl.stages.Stage
import swave.core.impl.{ Inport, Outport }
import swave.core.util._

// format: OFF
private[core] final class BroadcastStage(eagerCancel: Boolean) extends FanOutStage with PipeElem.FanOut.Broadcast {
  import Stage.OutportStates

  def pipeElemType: String = "fanOutBroadcast"
  def pipeElemParams: List[Any] = eagerCancel :: Nil

  connectFanOutAndStartWith { (ctx, in, outs) ⇒ running(in, outs, pending = 0) }

  /**
   * @param in        the active upstream
   * @param outs      the active downstreams
   * @param pending   number of elements already requested from upstream but not yet received, >= 0
   */
  def running(in: Inport, outs: OutportStates, pending: Long): State = {
    @tailrec def handleDemand(n: Int, out: Outport, outs: OutportStates, current: OutportStates,
                              minRemaining: Long): State =
      if (current ne null) {
        if (current.out eq out) current.remaining = current.remaining ⊹ n
        handleDemand(n, out, outs, current.tail, math.min(current.remaining, minRemaining))
      } else {
        if (minRemaining > pending) in.request(minRemaining - pending)
        running(in, outs, minRemaining)
      }

    @tailrec def handleOnComplete(current: OutportStates): State =
      if (current ne null) {
        current.out.onComplete()
        handleOnComplete(current.tail)
      } else stop()

    state(name = "running",
      request = (n, out) ⇒ handleDemand(n, out, outs, outs, Long.MaxValue),

      cancel = out ⇒ {
        @tailrec def rec(prev: OutportStates, current: OutportStates): State =
          if (current ne null) {
            if (current.out eq out) {
              val newOuts = if (prev ne null) { prev.tail = current.tail; outs } else current.tail
              if (eagerCancel || (newOuts eq null)) {
                in.cancel()
                handleOnComplete(newOuts)
              } else handleDemand(0, null, newOuts, newOuts, Long.MaxValue)
            } else rec(current, current.tail)
          } else unexpectedCancel(out)
        rec(null, outs)
      },

      onNext = (elem, _) ⇒ {
        @tailrec def rec(current: OutportStates): State =
          if (current ne null) {
            current.out.onNext(elem)
            current.remaining -= 1
            rec(current.tail)
          } else running(in, outs, pending - 1)
        rec(outs)
      },

      onComplete = _ ⇒ handleOnComplete(outs),

      onError = (e, _) ⇒ {
        @tailrec def rec(current: OutportStates): State =
          if (current ne null) {
            current.out.onError(e)
            rec(current.tail)
          } else stop()
        rec(outs)
      })
  }
}

