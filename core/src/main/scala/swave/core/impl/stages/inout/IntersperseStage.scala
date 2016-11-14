/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import swave.core.Stage
import swave.core.impl.{Inport, Outport}
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImplementation
private[core] final class IntersperseStage(start: AnyRef, inject: AnyRef, end: AnyRef) extends InOutStage  {

  def kind = Stage.Kind.InOut.Intersperse(start, inject, end)

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport) = {

    /**
      * Upstream and downstream active, no demand seen yet.
      */
    def awaitingFirstDemand(): State = state(
      request = (n, _) => {
        in.request(1)
        awaitingFirstElem(n.toLong)
      },

      cancel = stopCancelF(in),
      onComplete = _ => if (start ne null) awaitingDemandForFinalElement(start) else terminate(0),
      onError = stopErrorF(out))

    /**
      * Upstream and downstream active, first element pending from upstream.
      *
      * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingFirstElem(remaining: Long): State = {
      requireState(remaining > 0)
      state(
        request = (n, _) => awaitingFirstElem(remaining ⊹ n),
        cancel = stopCancelF(in),

        onNext = (elem, _) => {
          val rem =
            if (start ne null) {
              out.onNext(start)
              remaining - 1
            } else remaining
          if (rem > 0) {
            out.onNext(elem)
            if (rem > 1) {
              in.request(1)
              awaitingElem(rem - 1)
            } else awaitingDemand()
          } else awaitingDemandFor(elem)
        },

        onComplete = _ => {
          if (start ne null) {
            out.onNext(start)
            terminate(remaining - 1)
          } else terminate(remaining)
        },

        onError = stopErrorF(out))
    }

    /**
      * Upstream and downstream active, first element (plus initial separators) already pushed to downstream.
      * No open demand.
      */
    def awaitingDemand(): State = state(
      request = (n, _) => {
        in.request(1)
        awaitingElem(n.toLong)
      },

      cancel = stopCancelF(in),
      onComplete = _ => terminate(0),
      onError = stopErrorF(out))

    /**
      * Upstream and downstream active, next element pending from upstream.
      *
      * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingElem(remaining: Long): State = {
      requireState(remaining > 0)
      state(
        request = (n, _) => awaitingElem(remaining ⊹ n),
        cancel = stopCancelF(in),

        onNext = (elem, _) => {
          out.onNext(inject)
          if (remaining > 1) {
            out.onNext(elem)
            if (remaining > 2) {
              in.request(1)
              awaitingElem(remaining - 2)
            } else awaitingDemand()
          } else awaitingDemandFor(elem)
        },

        onComplete = _ => terminate(remaining),
        onError = stopErrorF(out))
    }

    /**
      * Upstream and downstream active, next element buffered.
      * No open demand.
      */
    def awaitingDemandFor(elem: AnyRef) = state(
      request = (n, _) => {
        out.onNext(elem)
        if (n > 1) {
          in.request(1)
          awaitingElem(n.toLong - 1)
        } else awaitingDemand()
      },

      cancel = stopCancelF(in),
      onComplete = _ => awaitingDemandForFinalElement(elem),
      onError = stopErrorF(out))

    /**
      * Upstream already cancelled, last element buffered.
      * No open demand.
      */
    def awaitingDemandForFinalElement(elem: AnyRef) = state(
      request = (n, _) => {
        out.onNext(elem)
        terminate(n.toLong - 1)
      },

      cancel = stopF)

    /**
      * Upstream already cancelled, `end` element to be sent.
      * No open demand.
      */
    def awaitingDemandForEnd() = state(
      request = (n, _) => {
        out.onNext(end)
        stopComplete(out)
      },

      cancel = stopF)

    // helper
    def terminate(remaining: Long) =
      if (remaining > 0) {
        if (end ne null) out.onNext(end)
        stopComplete(out)
      } else {
        if (end ne null) awaitingDemandForEnd()
        else stopComplete(out)
      }

    awaitingFirstDemand()
  }
}
