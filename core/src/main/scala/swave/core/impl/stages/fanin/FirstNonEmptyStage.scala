/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.fanin

import swave.core.PipeElem
import swave.core.impl.util.InportList
import swave.core.impl.{ Inport, Outport }
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImpl(fullInterceptions = true)
private[core] final class FirstNonEmptyStage(subs: InportList) extends FanInStage with PipeElem.FanIn.FirstNonEmpty {

  requireArg(subs.nonEmpty)

  def pipeElemType: String = "fanInFirstNonEmpty"
  def pipeElemParams: List[Any] = Nil

  connectFanInAndSealWith(subs) { (ctx, out) ⇒ awaitingFirstElement(subs, out, 0) }

  /**
   * Waiting for the first element to arrive from the ins head.
   *
   * @param ins     the active upstreams
   * @param pending the number of elements already requested from the ins head, >= 0
   */
  def awaitingFirstElement(ins: InportList, out: Outport, pending: Long): State = state(
    request = (n, _) ⇒ {
      ins.in.request(n.toLong)
      awaitingFirstElement(ins, out, pending ⊹ n)
    },

    cancel = stopCancelF(ins),

    onNext = (elem, _) ⇒ {
      out.onNext(elem)
      cancelAll(ins, except = ins.in)
      draining(ins.in, out)
    },

    onComplete = from ⇒ {
      if (from eq ins.in) {
        val tail = ins.tail
        if (tail.nonEmpty) {
          if (pending > 0) tail.in.request(pending) // retarget demand
          awaitingFirstElement(tail, out, pending)
        } else stopComplete(out)
      } else awaitingFirstElement(ins remove_! from, out, pending)
    },

    onError = cancelAllAndStopErrorF(ins, out))

  /**
   * Simply forwarding elements from the given `in` to downstream.
   */
  def draining(in: Inport, out: Outport) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),
    onNext = onNextF(out),

    onComplete = from => {
      // ignore completes from other ins that might have dispatched before our cancellation arrived
      if (from eq in) stopComplete(out)
      else stay()
    },

    onError = (e, from) => {
      if (from ne in) in.cancel()
      stopError(e, out)
    })
}
