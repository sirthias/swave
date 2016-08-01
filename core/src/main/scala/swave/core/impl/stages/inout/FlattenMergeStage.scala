/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import swave.core.impl.util.{ InportAnyRefList, InportList }
import swave.core.impl.stages.drain.SubDrainStage
import swave.core.{ PipeElem, Streamable }
import swave.core.macros._
import swave.core.util._
import swave.core.impl._

// format: OFF
@StageImpl(fullInterceptions = true)
private[core] final class FlattenMergeStage(streamable: Streamable.Aux[AnyRef, AnyRef],
                                             parallelism: Int, timeout: Duration)
  extends InOutStage with PipeElem.InOut.FlattenMerge {

  requireArg(parallelism > 0, "`parallelism` must be > 0")

  def pipeElemType: String = "flattenMerge"
  def pipeElemParams: List[Any] = parallelism :: timeout :: Nil

  // stores (sub, elem) records in the order they arrived so we can dispatch them quickly when they are requested
  private[this] val buffer: RingBuffer[InportAnyRefList] = new RingBuffer(roundUpToNextPowerOf2(parallelism))

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForXStart(this)
    running(ctx, in, out, timeout orElse ctx.env.settings.subscriptionTimeout)
  }

  def running(ctx: RunContext, in: Inport, out: Outport, subscriptionTimeout: Duration) = {

    def awaitingXStart() = state(
      xStart = () => {
        in.request(parallelism.toLong)
        awaitingSub(InportList.empty, 0)
      })

    /**
     * No subs subscribed, awaiting `onSubscribe` from any sub from the `subscribing` list
     *
     * @param subscribing subs from which we are awaiting an onSubscribe, may be empty
     * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
     */
    def awaitingSub(subscribing: InportList, remaining: Long): State = {
      requireState(remaining >= 0)
      state(
        onSubscribe = sub ⇒ active(activateSub(sub, subscribing), InportAnyRefList(sub), remaining),
        request = (n, _) ⇒ awaitingSub(subscribing, remaining ⊹ n),

        cancel = _ ⇒ {
          cancelAll(subscribing)
          stopCancel(in)
        },

        onNext = (elem, from) ⇒ {
          requireState(from eq in)
          val sub = subscribeSubDrain(elem)
          awaitingSub(sub +: subscribing, remaining)
        },

        onComplete = _ ⇒ {
          if (subscribing.isEmpty) stopComplete(out)
          else awaitingSubUpstreamCompleted(out, subscribing, remaining)
        },

        onError = (e, _) ⇒ {
          cancelAll(subscribing)
          stopError(e, out)
        })
    }

    /**
     * Buffer non-empty or at least one sub subscribed, potentially more subscribing.
     *
     * @param subscribing subs from which we are awaiting an onSubscribe, may be empty
     * @param subscribed  active subs, may be empty (but then the buffer must be non-empty)
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
            if (newSubscribed.nonEmpty || buffer.nonEmpty) active(subscribing, newSubscribed, remaining)
            else awaitingSub(subscribing, remaining)
          } else {
            if (subscribed.isEmpty && buffer.isEmpty) {
              if (subscribing.isEmpty) stopComplete(out)
              else awaitingSubUpstreamCompleted(out, subscribing, remaining)
            } else activeUpstreamCompleted(out, subscribing, subscribed, remaining)
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
      val sub = new SubDrainStage(ctx, this, subscriptionTimeout)
      streamable(elem).inport.subscribe()(sub)
      sub
    }

    awaitingXStart()
  }

  /**
   * Main upstream completed, one sub currently subscribing, no subs subscribed.
   *
   * @param out         the active downstream
   * @param subscribing subs from which we are awaiting an onSubscribe, non-empty
   * @param remaining   number of elements already requested by downstream but not yet delivered, >= 0
   */
  def awaitingSubUpstreamCompleted(out: Outport, subscribing: InportList, remaining: Long): State = {
    requireState(subscribing.nonEmpty && remaining >= 0)
    state(
      onSubscribe = sub ⇒ activeUpstreamCompleted(out, activateSub(sub, subscribing), InportAnyRefList(sub), remaining),
      request = (n, _) ⇒ awaitingSubUpstreamCompleted(out, subscribing, remaining ⊹ n),
      cancel = stopCancelF(subscribing))
  }

  /**
   * Main upstream completed, at least one sub subscribed, potentially more subscribing.
   *
   * @param out         the active downstream
   * @param subscribing subs from which we are awaiting an onSubscribe, may be empty
   * @param subscribed  active subs, non-empty
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
          } else if (subscribed.nonEmpty) activeUpstreamCompleted(out, subscribing, subscribed, n.toLong)
          else if (subscribing.nonEmpty) awaitingSubUpstreamCompleted(out, subscribing, remaining)
          else stopComplete(out)

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
        if (newSubscribed.nonEmpty) activeUpstreamCompleted(out, subscribing, newSubscribed, remaining)
        else if (subscribing.nonEmpty) awaitingSubUpstreamCompleted(out, subscribing, remaining)
        else stopComplete(out)
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