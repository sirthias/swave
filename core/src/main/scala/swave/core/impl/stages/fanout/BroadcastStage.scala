/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.fanout

import scala.annotation.tailrec
import swave.core.macros.StageImpl
import swave.core.PipeElem
import swave.core.impl.stages.Stage
import swave.core.impl.{ Inport, Outport }
import swave.core.util._
import Stage.OutportStates

// format: OFF
@StageImpl(fullInterceptions = true)
private[core] final class BroadcastStage(eagerCancel: Boolean) extends FanOutStage with PipeElem.FanOut.Broadcast {

  def pipeElemType: String = "fanOutBroadcast"
  def pipeElemParams: List[Any] = eagerCancel :: Nil

  connectFanOutAndSealWith { (ctx, in, outs) ⇒ running(in, outs, 0) }

  /**
   * @param in        the active upstream
   * @param outs      the active downstreams
   * @param pending   number of elements already requested from upstream but not yet received, >= 0
   */
  def running(in: Inport, outs: OutportStates, pending: Long): State = {

    @tailrec def handleDemand(n: Int, out: Outport, os: OutportStates, current: OutportStates,
                              minRemaining: Long): State =
      if (current ne null) {
        if (current.out eq out) current.remaining = current.remaining ⊹ n
        handleDemand(n, out, os, current.tail, math.min(current.remaining, minRemaining))
      } else {
        if (minRemaining > pending) in.request(minRemaining - pending)
        running(in, os, minRemaining)
      }

    @tailrec def handleOnComplete(current: OutportStates): State =
      if (current ne null) {
        current.out.onComplete()
        handleOnComplete(current.tail)
      } else stop()

    state(
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
          } else illegalState(s"Unexpected cancel() from out '$out' in $this")
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
          } else stop(e)
        rec(outs)
      })
  }
}
