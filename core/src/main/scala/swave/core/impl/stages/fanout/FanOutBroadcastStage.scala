/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.fanout

import scala.annotation.tailrec
import swave.core.macros.StageImplementation
import swave.core.Stage
import swave.core.impl.stages.FanOutStage
import swave.core.impl.{Inport, Outport}
import swave.core.util._

// format: OFF
@StageImplementation(fullInterceptions = true)
private[core] final class FanOutBroadcastStage(eagerCancel: Boolean) extends FanOutStage {

  def kind = Stage.Kind.FanOut.Broadcast(eagerCancel)

  type OutportCtx = FanOutStage.SimpleOutportContext

  protected def createOutportCtx(out: Outport, tail: OutportCtx): OutportCtx =
    new FanOutStage.SimpleOutportContext(out, tail)

  connectFanOutAndSealWith { (ctx, in, outs) ⇒ running(in, outs, 0) }

  /**
    * @param in        the active upstream
    * @param outs      the active downstreams
    * @param pending   number of elements already requested from upstream but not yet received, >= 0
    */
  def running(in: Inport, outs: OutportCtx, pending: Long): State = {

    @tailrec def handleDemand(n: Int, out: Outport, os: OutportCtx, current: OutportCtx,
                              minRemaining: Long): State =
      if (current ne null) {
        if (current.out eq out) current.remaining = current.remaining ⊹ n
        handleDemand(n, out, os, current.tail, math.min(current.remaining, minRemaining))
      } else {
        if (minRemaining > pending) in.request(minRemaining - pending)
        running(in, os, minRemaining)
      }

    @tailrec def completeAll(current: OutportCtx): State =
      if (current ne null) {
        current.out.onComplete()
        completeAll(current.tail)
      } else stop()

    state(
      request = (n, out) ⇒ handleDemand(n, out, outs, outs, Long.MaxValue),

      cancel = out ⇒ {
        @tailrec def rec(prev: OutportCtx, current: OutportCtx): State =
          if (current ne null) {
            if (current.out eq out) {
              val newOuts = if (prev ne null) { prev.tail = current.tail; outs } else current.tail
              if (eagerCancel || (newOuts eq null)) {
                in.cancel()
                completeAll(newOuts)
              } else handleDemand(0, null, newOuts, newOuts, Long.MaxValue)
            } else rec(current, current.tail)
          } else stay()
        rec(null, outs)
      },

      onNext = (elem, _) ⇒ {
        @tailrec def rec(current: OutportCtx): State =
          if (current ne null) {
            current.out.onNext(elem)
            current.remaining -= 1
            rec(current.tail)
          } else running(in, outs, pending - 1)
        rec(outs)
      },

      onComplete = _ ⇒ completeAll(outs),

      onError = (e, _) ⇒ {
        @tailrec def rec(current: OutportCtx): State =
          if (current ne null) {
            current.out.onError(e)
            rec(current.tail)
          } else stop(e)
        rec(outs)
      })
  }
}
