/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.Stage
import swave.core.impl.stages.InOutStage
import swave.core.impl.{Inport, Outport}
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImplementation(interceptAllRequests = true)
private[core] final class ExpandStage(zero: Iterator[AnyRef], extrapolate: Any => Iterator[AnyRef])
  extends InOutStage {
  import ExpandStage._

  def kind = Stage.Kind.InOut.Expand(zero, extrapolate)

  connectInOutAndSealWith { (in, out) ⇒
    region.impl.registerForXStart(this)
    running(in, out)
  }

  def running(in: Inport, out: Outport) = {

    def awaitingXStart() = state(
      xStart = () => {
        in.request(1)
        if (zero.hasNext) awaitingNextElementOrDemand(zero)
        else awaitingElem(0)
      })


    /**
      * Last expansion iterator completed, one element pending from upstream.
      * Awaiting next element from upstream.
      *
      * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingElem(remaining: Long): State = {
      requireState(remaining >= 0)
      state(
        request = (n, _) => awaitingElem(remaining ⊹ n),
        cancel = stopCancelF(in),
        onNext = (elem, _) ⇒ handleElement(elem, remaining),
        onComplete = stopCompleteF(out),
        onError = stopErrorF(out),
        xEvent = { case Trigger => stay() })
    }

    /**
      * Fresh (i.e. unpulled) expansion iterator available. No element pending from upstream.
      * Awaiting demand from downstream.
      *
      * @param iterator  the expansion iterator, non-empty
      */
    def awaitingDemand(iterator: Iterator[AnyRef]): State = {
      requireState(iterator.hasNext)
      state(
        request = (n, _) => {
          in.request(1)
          emit(iterator, n.toLong)
        },

        cancel = stopCancelF(in),
        onComplete = _ => drainingFinalElem(out, iterator),
        onError = stopErrorF(out),
        xEvent = { case Trigger => stay() })
    }

    /**
      * Expansion iterator active and already pulled from at least once. One element pending from upstream.
      * Awaiting next element from upstream or Trigger for emitting to downstream.
      *
      * @param iterator  the expansion iterator, non-empty
      * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def expanding(iterator: Iterator[AnyRef], remaining: Long): State = {
      requireState(iterator.hasNext && remaining >= 0)
      state(
        request = (n, _) => expanding(iterator, remaining ⊹ n),
        cancel = stopCancelF(in),
        onNext = (elem, _) ⇒ handleElement(elem, remaining),
        onComplete = stopCompleteF(out),
        onError = stopErrorF(out),
        xEvent = { case Trigger => emit(iterator, remaining) })
    }

    /**
      * Expansion iterator active and already pulled from at least once. One element pending from upstream.
      * Awaiting next element from upstream or demand from downstream.
      *
      * @param iterator  the expansion iterator, non-empty
      */
    def awaitingNextElementOrDemand(iterator: Iterator[AnyRef]): State = {
      requireState(iterator.hasNext)
      state(
        request = (n, _) => emit(iterator, n.toLong),
        cancel = stopCancelF(in),
        onNext = (elem, _) ⇒ handleElement(elem, 0L),
        onComplete = stopCompleteF(out),
        onError = stopErrorF(out),
        xEvent = { case Trigger => stay() })
    }

    def handleElement(elem: AnyRef, remaining: Long): State = {
      var funError: Throwable = null
      val next = try extrapolate(elem) catch { case NonFatal(e) => { funError = e; null } }
      if (funError eq null) {
        if (next.hasNext) {
          if (remaining > 0) {
            in.request(1)
            emit(next, remaining)
          } else awaitingDemand(next)
        } else {
          in.request(1)
          awaitingElem(remaining)
        }
      } else fail(funError)
    }

    def emit(iterator: Iterator[AnyRef], remaining: Long): State = {
      requireState(iterator.hasNext && remaining > 0)
      var iterError: Throwable = null
      val nextElement = try iterator.next() catch { case NonFatal(e) => { iterError = e; null } }
      if (iterError eq null) {
        out.onNext(nextElement)
        val newRemaining = remaining - 1
        if (iterator.hasNext) {
          if (newRemaining > 0) {
            xEvent(Trigger)
            expanding(iterator, newRemaining)
          } else awaitingNextElementOrDemand(iterator)
        } else awaitingElem(newRemaining)
      } else fail(iterError)
    }

    def fail(e: Throwable) = {
      in.cancel()
      stopError(e, out)
    }

    awaitingXStart()
  }

  /**
    * Upstream already completed.
    * Awaiting demand from downstream to emit first element from fresh (i.e. unpulled) expansion iterator
    * before completing.
    *
    * @param out       the active downstream
    * @param iterator  the expansion iterator, non-empty
    */
  def drainingFinalElem(out: Outport, iterator: Iterator[AnyRef]): State = {
    requireState(iterator.hasNext)
    state(
      request = (_, _) => {
        var funError: Throwable = null
        val elem = try iterator.next() catch { case NonFatal(e) => { funError = e; null } }
        if (funError eq null) {
          out.onNext(elem)
          stopComplete(out)
        } else stopError(funError, out)
      },

      cancel = stopF)
  }
}

private[core] object ExpandStage {
  private case object Trigger
}