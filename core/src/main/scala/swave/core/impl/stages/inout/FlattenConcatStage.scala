/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.concurrent.duration.Duration
import swave.core.impl._
import swave.core.impl.stages.drain.SubDrainStage
import swave.core.macros.StageImpl
import swave.core.{ PipeElem, Streamable }
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class FlattenConcatStage(streamable: Streamable.Aux[AnyRef, AnyRef],
                                             parallelism: Int, timeout: Duration)
  extends InOutStage with PipeElem.InOut.FlattenConcat {

  requireArg(parallelism > 0)

  def pipeElemType: String = "flattenConcat"
  def pipeElemParams: List[Any] = parallelism :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForXStart(this)
    running(ctx, in, out, if (timeout eq Duration.Undefined) ctx.env.settings.subscriptionTimeout else timeout)
  }

  // TODO: switch to fully parallel subscribe of all subs at once
  // implementation idea: we need a third list containing the already subscribed but not yet "current" subs

  def running(ctx: RunContext, in: Inport, out: Outport, subscriptionTimeout: Duration) = {

    def awaitingXStart() = state(
      xStart = () => {
        in.request(1)
        awaitingSub(0)
      })

    /**
     * No subs currently subscribing or subscribed.
     *
     * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
     */
    def awaitingSub(remaining: Long): State = state(
      request = (n, _) ⇒ awaitingSub(remaining ⊹ n),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        val sub = subscribeSubDrain(elem)
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
    def awaitingSubOnSubscribe(subscribing: SubDrainStage, remaining: Long): State = state(
      onSubscribe = sub ⇒ {
        requireState(sub eq subscribing)
        subscribing.sealAndStart()
        if (remaining > 0) subscribing.request(remaining)
        if (parallelism > 1) in.request(1)
        active(1, null, InportList(sub), remaining, remaining)
      },

      request = (n, _) ⇒ awaitingSubOnSubscribe(subscribing, remaining ⊹ n),

      cancel = _ ⇒ {
        in.cancel()
        stopCancel(subscribing)
      },

      onComplete = _ ⇒ drainingWaitingForOnSubscribe(out, subscribing, remaining),

      onError = (e, _) ⇒ {
        out.onError(e)
        stopCancel(subscribing)
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
    def active(subCount: Int, subscribing: SubDrainStage, subscribed: InportList, pending: Long,
               remaining: Long): State = state(
      onSubscribe = sub ⇒ {
        requireState(sub eq subscribing)
        subscribing.sealAndStart()
        if (subCount < parallelism) in.request(1)
        active(subCount, null, subscribed :+ sub, pending, remaining)
      },

      request = (n, _) ⇒ {
        val newPending =
          if (pending == 0) {
            requireState(remaining == 0)
            val nl = n.toLong
            val x = subscribed.in
            x.request(nl)
            nl
          } else pending
        active(subCount, subscribing, subscribed, newPending, remaining ⊹ n)
      },

      cancel = _ ⇒ {
        in.cancel()
        cancelAll(subscribed)
        if (subscribing ne null) stopCancel(subscribing) else stop()
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
          val sub = subscribeSubDrain(elem)
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
              } else { requireState(subscribing ne null); awaitingSubOnSubscribe(subscribing, remaining) }
            } else active(subCount - 1, subscribing, subscribed remove_! from, pending, remaining)
          } else awaitingSub(remaining)
        } else draining(out, subscribing, subscribed, pending, remaining)
      },

      onError = (e, from) ⇒ {
        if (from ne in) in.cancel()
        cancelAll(subscribed, from)
        out.onError(e)
        if (subscribing ne null) stopCancel(subscribing) else stop()
      })

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
   * @param subscribing sub from which we are awaiting an onSubscribe
   * @param remaining   number of elements already requested by downstream but not yet delivered, >= 0
   */
  def drainingWaitingForOnSubscribe(out: Outport, subscribing: SubDrainStage, remaining: Long): State = state(
    onSubscribe = sub ⇒ {
      requireState(sub eq subscribing)
      subscribing.sealAndStart()
      if (remaining > 0) subscribing.request(remaining)
      draining(out, null, InportList(sub), remaining, remaining)
    },

    request = (n, _) ⇒ drainingWaitingForOnSubscribe(out, subscribing, remaining ⊹ n),
    cancel = stopCancelF(subscribing))

  /**
   * Main upstream completed, at least one sub subscribed, potentially one subscribing.
   *
   * @param out         the active downstream
   * @param subscribing sub from which we are awaiting an onSubscribe, may be null
   * @param subscribed  active subs, non-empty
   * @param pending     number of elements already requested from the head subscribed sub but not yet received, >= 0
   * @param remaining   number of elements already requested by downstream but not yet delivered, >= 0
   */
  def draining(out: Outport, subscribing: SubDrainStage, subscribed: InportList, pending: Long,
               remaining: Long): State = state(

    onSubscribe = sub ⇒ {
      requireState(sub eq subscribing)
      subscribing.sealAndStart()
      draining(out, null, subscribed :+ sub, pending, remaining)
    },

    request = (n, _) ⇒ {
      val newPending =
        if (pending == 0) {
          requireState(remaining == 0)
          val nl = n.toLong
          val x = subscribed.in
          x.request(nl)
          nl
        } else pending
      draining(out, subscribing, subscribed, newPending, remaining ⊹ n)
    },

    cancel = _ ⇒ {
      cancelAll(subscribed)
      if (subscribing ne null) stopCancel(subscribing) else stop()
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
      draining(out, subscribing, subscribed, newPending, remaining - 1)
    },

    onComplete = from ⇒ {
      if (from eq subscribed.in) { // if the current sub completed
        if (subscribed.tail.nonEmpty) { // and we have the next one ready
          subscribed.tail.in.request(remaining) // retarget demand
          draining(out, subscribing, subscribed.tail, remaining, remaining)
        } else if (subscribing eq null) stopComplete(out)
        else drainingWaitingForOnSubscribe(out, subscribing, remaining)
      } else draining(out, subscribing, subscribed remove_! from, pending, remaining)
    },

    onError = (e, sub) ⇒ {
      cancelAll(subscribed, sub)
      out.onError(e)
      if (subscribing ne null) stopCancel(subscribing) else stop()
    })
}
