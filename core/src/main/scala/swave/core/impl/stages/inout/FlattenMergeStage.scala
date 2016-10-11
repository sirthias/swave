/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.annotation.tailrec
import swave.core.impl.util.{InportAnyRefList, InportList}
import swave.core.impl.stages.drain.SubDrainStage
import swave.core.{PipeElem, Streamable}
import swave.core.macros._
import swave.core.util._
import swave.core.impl._

// format: OFF
@StageImpl(fullInterceptions = true)
private[core] final class FlattenMergeStage(streamable: Streamable.Aux[AnyRef, AnyRef], parallelism: Int)
  extends InOutStage with PipeElem.InOut.FlattenMerge {

  requireArg(parallelism > 0, "`parallelism` must be > 0")

  def pipeElemType: String = "flattenMerge"
  def pipeElemParams: List[Any] = parallelism :: Nil

  // stores (sub, elem) records in the order they arrived so we can dispatch them quickly when they are requested
  private[this] val buffer: RingBuffer[InportAnyRefList] = new RingBuffer(roundUpToPowerOf2(parallelism))

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForXStart(this)
    running(ctx, in, out)
  }

  def running(ctx: RunContext, in: Inport, out: Outport) = {

    def awaitingXStart() = state(
      xStart = () => {
        in.request(parallelism.toLong)
        active(InportList.empty, InportAnyRefList.empty, 0)
      })

    /**
      * Upstream active.
      *
      * @param subscribing subs from which we are awaiting an onSubscribe, may be empty
      * @param subscribed  active subs, may be empty
      * @param remaining   number of elements already requested by downstream but not yet delivered, >= 0
      */
    def active(subscribing: InportList, subscribed: InportAnyRefList, remaining: Long): State = {
      requireState(remaining >= 0)
      state(
        onSubscribe = sub ⇒ active(activateSub(sub, subscribing), sub +: subscribed, remaining),

        request = (n, _) ⇒ {
          @tailrec def rec(n: Int): State =
            if (buffer.nonEmpty) {
              if (n > 0) {
                val record = buffer.unsafeRead()
                out.onNext(record.value)
                record.value = null
                // if the sub has completed (which is signalled by `tail` pointing to self) we request the next sub
                // otherwise we request the next element from the current sub
                (if (record.tail eq record) in else record.in).request(1)
                rec(n - 1)
              } else stay()
            } else active(subscribing, subscribed, n.toLong)

          if (remaining > 0) {
            requireState(buffer.isEmpty)
            active(subscribing, subscribed, remaining ⊹ n)
          } else rec(n)
        },

        cancel = _ ⇒ {
          cancelAll(subscribing)
          cancelAll(subscribed)
          stopCancel(in)
        },

        onNext = (elem, from) ⇒ {
          if (from ne in) {
            if (remaining > 0) {
              out.onNext(elem)
              from.request(1)
              active(subscribing, subscribed, remaining - 1)
            } else store(elem, from, subscribed)
          } else active(subscribeSubDrain(elem) +: subscribing, subscribed, remaining)
        },

        onComplete = from ⇒ {
          if (from ne in) {
            @tailrec def removeFrom(last: InportAnyRefList, current: InportAnyRefList): InportAnyRefList = {
              requireState(current ne null, "`from` wasn't found in the `subscribed` list")
              if (current.in eq from) {
                val tail = current.tail
                // if there is still a value buffered for the sub we signal that the node has been removed by
                // setting `tail` to self, otherwise we immediately request the next sub
                if (current.value ne null) current.tail = current else in.request(1)
                if (last ne null) {
                  last.tail = tail
                  subscribed
                } else tail
              } else removeFrom(current, current.tail)
            }
            val newSubscribed = removeFrom(null, subscribed)
            active(subscribing, newSubscribed, remaining)
          } else {
            if (subscribing.isEmpty && subscribed.isEmpty && buffer.isEmpty) stopComplete(out)
            else activeUpstreamCompleted(out, subscribing, subscribed, remaining)
          }
        },

        onError = (e, from) ⇒ {
          if (from ne in) in.cancel()
          cancelAll(subscribed, except = from)
          cancelAll(subscribing)
          stopError(e, out)
        })
    }

    def subscribeSubDrain(elem: AnyRef): SubDrainStage = {
      val sub = new SubDrainStage(ctx, this)
      streamable(elem).inport.subscribe()(sub)
      sub
    }

    awaitingXStart()
  }

  /**
    * Main upstream completed.
    *
    * @param out         the active downstream
    * @param subscribing subs from which we are awaiting an onSubscribe, may be empty
    * @param subscribed  active subs, ma be empty
    * @param remaining   number of elements already requested by downstream but not yet delivered, >= 0
    */
  def activeUpstreamCompleted(out: Outport, subscribing: InportList, subscribed: InportAnyRefList,
                              remaining: Long): State = {
    requireState(remaining >= 0)
    state(
      onSubscribe = sub ⇒ activeUpstreamCompleted(out, activateSub(sub, subscribing), sub +: subscribed, remaining),

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
          } else if (subscribing.isEmpty && subscribed.isEmpty) stopComplete(out)
          else activeUpstreamCompleted(out, subscribing, subscribed, n.toLong)

        if (remaining > 0) {
          requireState(buffer.isEmpty)
          activeUpstreamCompleted(out, subscribing, subscribed, remaining ⊹ n)
        } else rec(n)
      },

      cancel = _ ⇒ {
        cancelAll(subscribing)
        stopCancel(subscribed)
      },

      onNext = (elem, from) ⇒ {
        if (remaining > 0) {
          out.onNext(elem)
          from.request(1)
          activeUpstreamCompleted(out, subscribing, subscribed, remaining - 1)
        } else store(elem, from, subscribed)
      },

      onComplete = from ⇒ {
        val newSubscribed = subscribed.remove_!(from)
        if (subscribing.isEmpty && newSubscribed.isEmpty) stopComplete(out)
        else activeUpstreamCompleted(out, subscribing, newSubscribed, remaining)
      },

      onError = (e, from) ⇒ {
        cancelAll(subscribed, except = from)
        cancelAll(subscribing)
        stopError(e, out)
      })
  }

  private def activateSub(sub: Inport, subscribing: InportList): InportList = {
    val newSubscribing = subscribing.remove_!(sub)
    val subStage = sub.asInstanceOf[SubDrainStage]
    subStage.sealAndStart()
    subStage.request(1)
    newSubscribing
  }

  @tailrec private def store(elem: AnyRef, from: Inport, current: InportAnyRefList): State = {
    requireState(current.nonEmpty)
    if (current.in eq from) {
      current.value = elem
      requireState(buffer.canWrite)
      buffer.write(current)
      stay()
    } else store(elem, from, current.tail)
  }
}