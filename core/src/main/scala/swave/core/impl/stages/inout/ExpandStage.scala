/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.annotation.tailrec
import scala.util.control.NonFatal
import swave.core.PipeElem
import swave.core.impl.{ Inport, Outport }
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class ExpandStage(zero: Iterator[AnyRef], extrapolate: AnyRef => Iterator[AnyRef])
  extends InOutStage with PipeElem.InOut.Expand {

  def pipeElemType: String = "expand"
  def pipeElemParams: List[Any] = zero :: extrapolate :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForXStart(this)
    awaitingXStart(in, out)
  }

  /**
   * @param in  the active upstream
   * @param out the active downstream
   */
  def awaitingXStart(in: Inport, out: Outport) = state(
    xStart = () => {
      in.request(1)
      if (zero.hasNext) expanding(in, out, zero)
      else awaitingElem(in, out, 0)
    })

  /**
   * Last expansion iterator completed. Awaiting next element from upstream.
   *
   * @param in        the active upstream
   * @param out       the active downstream
   * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
   */
  def awaitingElem(in: Inport, out: Outport, remaining: Long): State = {
    requireState(remaining >= 0)
    state(
      request = (n, _) => awaitingElem(in, out, remaining ⊹ n),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        var funError: Throwable = null
        val next = try extrapolate(elem) catch { case NonFatal(e) => { funError = e; null } }
        if (funError eq null) {
          if (next.hasNext) {
            if (remaining > 0) emit(in, out, next, remaining)
            else awaitingDemand(in, out, next)
          } else awaitingElem(in, out, remaining)
        } else fail(in, out, funError)
      },

      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))
  }

  /**
   * Fresh (i.e. unpulled) expansion iterator available.
   * Awaiting demand from downstream.
   *
   * @param in        the active upstream
   * @param out       the active downstream
   * @param iterator  the expansion iterator, non-empty
   */
  def awaitingDemand(in: Inport, out: Outport, iterator: Iterator[AnyRef]): State = {
    requireState(iterator.hasNext)
    state(
      request = (n, _) => {
        in.request(1)
        emit(in, out, iterator, n.toLong)
      },

      cancel = stopCancelF(in),
      onComplete = _ => drainingFinalElem(out, iterator),
      onError = stopErrorF(out))
  }

  /**
   * Expansion iterator active and already pulled from at least once.
   * No unfulfilled demand from downstream.
   *
   * @param in        the active upstream
   * @param out       the active downstream
   * @param iterator  the expansion iterator, non-empty
   */
  def expanding(in: Inport, out: Outport, iterator: Iterator[AnyRef]): State = {
    requireState(iterator.hasNext)
    state(
      request = (n, _) => emit(in, out, iterator, n.toLong),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        var funError: Throwable = null
        val nextIterator = try extrapolate(elem) catch { case NonFatal(e) => { funError = e; null } }
        if (funError eq null) awaitingDemand(in, out, nextIterator)
        else fail(in, out, funError)
      },

      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))
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

  @tailrec private def emit(in: Inport, out: Outport, iterator: Iterator[AnyRef], remaining: Long): State = {
    requireState(iterator.hasNext && remaining > 0)
    var iterError: Throwable = null
    val nextElement = try iterator.next() catch { case NonFatal(e) => { iterError = e; null } }
    if (iterError eq null) {
      out.onNext(nextElement)
      val newRemaining = remaining - 1
      if (iterator.hasNext) {
        if (newRemaining > 0) emit(in, out, iterator, newRemaining)
        else expanding(in, out, iterator)
      } else awaitingElem(in, out, newRemaining)
    } else fail(in, out, iterError)
  }

  private def fail(in: Inport, out: Outport, e: Throwable) = {
    in.cancel()
    stopError(e, out)
  }
}