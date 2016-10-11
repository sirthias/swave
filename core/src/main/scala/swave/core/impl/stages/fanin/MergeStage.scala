/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.fanin

import scala.annotation.tailrec
import swave.core.PipeElem
import swave.core.impl.util.{InportAnyRefList, InportList}
import swave.core.impl.Outport
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImpl(fullInterceptions = true)
private[core] final class MergeStage(subs: InportList, eagerComplete: Boolean)
  extends FanInStage with PipeElem.FanIn.Concat {

  requireArg(subs.nonEmpty)

  def pipeElemType: String = "fanInMerge"
  def pipeElemParams: List[Any] = Nil

  // stores (sub, elem) records in the order they arrived so we can dispatch them quickly when they are requested
  private[this] val buffer: RingBuffer[InportAnyRefList] = new RingBuffer(roundUpToPowerOf2(subs.size))

  connectFanInAndSealWith(subs) { (ctx, out) ⇒
    ctx.registerForXStart(this)
    awaitingXStart(out)
  }

  /**
    * @param out the active downstream
    */
  def awaitingXStart(out: Outport) = state(
    xStart = () => {
      @tailrec def rec(rest: InportList, result: InportAnyRefList): InportAnyRefList =
        if (rest.nonEmpty) {
          rest.in.request(1)
          rec(rest.tail, rest.in +: result)
        } else result

      running(out, rec(subs, InportAnyRefList.empty), 0)
    })

  /**
    * @param out       the active downstream
    * @param ins       the active upstreams
    * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
    */
  def running(out: Outport, ins: InportAnyRefList, remaining: Long): State = state(
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

      if (remaining > 0) {
        requireState(buffer.isEmpty)
        running(out, ins, remaining ⊹ n)
      } else rec(n)
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
        cancelAll(ins, except = from)
        if (buffer.isEmpty) stopComplete(out) else draining(out)
      } else running(out, ins remove_! from, remaining)
    },

    onError = cancelAllAndStopErrorF(ins, out))

  /**
    * Upstreams completed, downstream active and buffer non-empty.
    *
    * @param out the active downstream
    */
  def draining(out: Outport) = state(
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
