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

import swave.core.impl._
import swave.core.macros.StageImpl
import swave.core.{ PipeElem, Streamable }
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class FlattenConcatStage(streamable: Streamable.Aux[AnyRef, AnyRef],
                                             parallelism: Int) extends InOutStage with PipeElem.InOut.FlattenConcat {
  require(parallelism > 0)

  def pipeElemType: String = "flattenConcat"
  def pipeElemParams: List[Any] = parallelism :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForXStart(this)
    awaitingXStart(ctx, in, out)
  }

  /**
   * @param ctx the RunContext
   * @param in  the active upstream
   * @param out the active downstream
   */
  def awaitingXStart(ctx: RunContext, in: Inport, out: Outport) = state(
    xStart = () => {
      in.request(1)
      running(ctx, in, out)
    })

  // TODO: switch to fully parallel subscribe of all subs at once
  // implementation idea: we need a third list containing the already subscribed but not yet "current" subs

  def running(ctx: RunContext, in: Inport, out: Outport) = {

    /**
     * No subs currently subscribing or subscribed.
     *
     * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
     */
    def awaitingSub(remaining: Long): State = state(
      request = (n, _) ⇒ awaitingSub(remaining ⊹ n),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        val sub = streamable(elem).inport
        sub.subscribe()
        awaitingSubOnSubscribe(sub, remaining)
      },

      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    /**
     * One sub currently subscribing, no subs subscribed.
     *
     * @param subscribing sub from which we are awaiting an onSubscribe
     * @param remaining   number of elements already requested by downstream but not yet delivered, >= 0
     */
    def awaitingSubOnSubscribe(subscribing: Inport, remaining: Long): State = state(
      onSubscribe = sub ⇒ {
        requireState(sub eq subscribing)
        ctx.start(sub)
        if (remaining > 0) sub.request(remaining)
        if (parallelism > 1) in.request(1)
        active(1, null, InportList(sub), remaining, remaining)
      },

      request = (n, _) ⇒ awaitingSubOnSubscribe(subscribing, remaining ⊹ n),

      cancel = _ ⇒ {
        in.cancel()
        cancelling(ctx, subscribing)
      },

      onComplete = _ ⇒ drainingWaitingForOnSubscribe(ctx, out, subscribing, remaining),

      onError = (e, _) ⇒ {
        out.onError(e)
        cancelling(ctx, subscribing)
      })

    /**
     * At least one sub subscribed, potentially one subscribing.
     *
     * @param subCount    number of subs that are currently subscribing or subscribed, > 0
     * @param subscribing sub from which we are awaiting an onSubscribe, may be null
     * @param subscribed  active subs, non-empty
     * @param pending     number of elements already requested from the head subscribed sub but not yet received, >= 0
     * @param remaining   number of elements already requested by downstream but not yet delivered, >= 0
     */
    def active(subCount: Int, subscribing: Inport, subscribed: InportList, pending: Long, remaining: Long): State = state(
      onSubscribe = sub ⇒ {
        requireState(sub eq subscribing)
        ctx.start(sub)
        if (subCount < parallelism) in.request(1)
        active(subCount, null, subscribed :+ sub, pending, remaining)
      },

      request = (n, _) ⇒ {
        val newPending =
          if (pending == 0) {
            requireState(remaining == 0)
            subscribed.in.request(n.toLong)
            n.toLong
          } else pending
        active(subCount, subscribing, subscribed, newPending, remaining ⊹ n)
      },

      cancel = _ ⇒ {
        in.cancel()
        cancelAll(subscribed)
        if (subscribing ne null) cancelling(ctx, subscribing) else stop()
      },

      onNext = (elem, from) ⇒ {
        if (from ne in) {
          out.onNext(elem)
          val newPending =
            if (pending == 1) {
              if (remaining > 1) {
                from.request(remaining - 1)
                remaining - 1
              } else 0
            } else pending - 1
          active(subCount, subscribing, subscribed, newPending, remaining - 1)
        } else {
          requireState(subscribing eq null)
          val sub = streamable(elem).inport
          sub.subscribe()
          active(subCount + 1, sub, subscribed, pending, remaining)
        }
      },

      onComplete = from ⇒ {
        if (from ne in) {
          in.request(1) // a sub completed, so we immediately request the next one
          if (subCount > 1) {
            if (from eq subscribed.in) { // if the current sub completed
              if (subscribed.tail.nonEmpty) { // and we have the next one ready
                subscribed.tail.in.request(remaining) // retarget demand
                active(subCount - 1, subscribing, subscribed.tail, remaining, remaining)
              } else { requireNonNull(subscribing); awaitingSubOnSubscribe(subscribing, remaining) }
            } else active(subCount - 1, subscribing, subscribed remove_! from, pending, remaining)
          } else awaitingSub(remaining)
        } else draining(ctx, out, subscribing, subscribed, pending, remaining)
      },

      onError = (e, from) ⇒ {
        if (from ne in) in.cancel()
        cancelAll(subscribed, from)
        out.onError(e)
        if (subscribing ne null) cancelling(ctx, subscribing) else stop()
      })

    awaitingSub(0)
  }

  /**
   * Main upstream completed, one sub currently subscribing, no subs subscribed.
   *
   * @param out         the active downstream
   * @param subscribing sub from which we are awaiting an onSubscribe
   * @param remaining   number of elements already requested by downstream but not yet delivered, >= 0
   */
  def drainingWaitingForOnSubscribe(ctx: RunContext, out: Outport, subscribing: Inport, remaining: Long): State = state(
    onSubscribe = sub ⇒ {
      ctx.start(sub)
      if (remaining > 0) sub.request(remaining)
      draining(ctx, out, null, InportList(sub), remaining, remaining)
    },

    request = (n, _) ⇒ drainingWaitingForOnSubscribe(ctx, out, subscribing, remaining ⊹ n),
    cancel = _ ⇒ cancelling(ctx, subscribing))

  /**
   * Main upstream completed, at least one sub subscribed, potentially one subscribing.
   *
   * @param out         the active downstream
   * @param subscribing sub from which we are awaiting an onSubscribe, may be null
   * @param subscribed  active subs, non-empty
   * @param pending     number of elements already requested from the head subscribed sub but not yet received, >= 0
   * @param remaining   number of elements already requested by downstream but not yet delivered, >= 0
   */
  def draining(ctx: RunContext, out: Outport, subscribing: Inport, subscribed: InportList, pending: Long,
               remaining: Long): State = state(

    onSubscribe = sub ⇒ {
      requireState(sub eq subscribing)
      ctx.start(sub)
      draining(ctx, out, null, subscribed :+ sub, pending, remaining)
    },

    request = (n, _) ⇒ {
      val newPending =
        if (pending == 0) {
          requireState(remaining == 0)
          subscribed.in.request(n.toLong)
          n.toLong
        } else pending
      draining(ctx, out, subscribing, subscribed, newPending, remaining ⊹ n)
    },

    cancel = _ ⇒ {
      cancelAll(subscribed)
      if (subscribing ne null) cancelling(ctx, subscribing) else stop()
    },

    onNext = (elem, sub) ⇒ {
      out.onNext(elem)
      val newPending =
        if (pending == 1) {
          if (remaining > 1) {
            sub.request(remaining - 1)
            remaining - 1
          } else 0
        } else pending - 1
      draining(ctx, out, subscribing, subscribed, newPending, remaining - 1)
    },

    onComplete = from ⇒ {
      if (from eq subscribed.in) { // if the current sub completed
        if (subscribed.tail.nonEmpty) { // and we have the next one ready
          subscribed.tail.in.request(remaining) // retarget demand
          draining(ctx, out, subscribing, subscribed.tail, remaining, remaining)
        } else if (subscribing eq null) stopComplete(out)
        else drainingWaitingForOnSubscribe(ctx, out, subscribing, remaining)
      } else draining(ctx, out, subscribing, subscribed remove_! from, pending, remaining)
    },

    onError = (e, sub) ⇒ {
      cancelAll(subscribed, sub)
      out.onError(e)
      if (subscribing ne null) cancelling(ctx, subscribing) else stop()
    })

  /**
   * Waiting for onSubscribe on given Inport to immediately cancel.
   *
   * @param subscribing the Inport we need to immediately cancel upon onSubscribe
   */
  def cancelling(ctx: RunContext, subscribing: Inport): State = state(
    onSubscribe = sub => {
      ctx.start(sub)
      stopCancel(sub)
    })
}