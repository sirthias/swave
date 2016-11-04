/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import scala.concurrent.duration._
import swave.core.{Cancellable, PipeElem}
import swave.core.impl.{Inport, Outport, StreamRunner}
import swave.core.impl.util.NanoTimeTokenBucket
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class ThrottleStage(cost: Int, per: FiniteDuration, burst: Int, costFn: AnyRef ⇒ Int)
  extends InOutStage with PipeElem.InOut.Throttle {

  requireArg(cost > 0, "cost must be > 0")
  requireArg(per > Duration.Zero, "per time must be > 0")
  requireArg(burst >= 0, "burst must be >= 0")
  requireArg(per.toNanos >= cost, "rates larger than 1/ns are not supported")

  private[this] val nanosBetweenTokens = per.toNanos / cost // we accept the small loss in precision due to rounding
  private[this] val tokenBucket = new NanoTimeTokenBucket(burst.toLong, nanosBetweenTokens)

  def pipeElemType: String = "throttle"
  def pipeElemParams: List[Any] = cost :: per :: burst :: costFn :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForRunnerAssignment(this)
    ctx.registerForXStart(this)
    running(in, out)
  }

  def running(in: Inport, out: Outport) = {

    def awaitingXStart() = state(
      xStart = () => {
        in.request(1)
        tokenBucket.reset()
        awaitingElement(0)
      })

    /**
      * Awaiting arrival of the next element from upstream.
      *
      * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingElement(remaining: Long): State = state(
      request = (n, _) => awaitingElement(remaining ⊹ n),
      cancel = stopCancelF(in),
      onNext = (elem, _) => if (remaining > 0) processElement(elem, remaining) else awaitingDemand(elem),
      onComplete = _ => stopComplete(out),
      onError = (e, _) => stopError(e, out))

    /**
      * One element buffered, awaiting demand from downstream.
      *
      * @param currentElem the buffered element
      */
    def awaitingDemand(currentElem: AnyRef) = state(
      request = (n, _) => processElement(currentElem, n.toLong),
      cancel = stopCancelF(in),
      onComplete = _ => upstreamCompletedAwaitingDemand(currentElem),
      onError = (e, _) => stopError(e, out))

    /**
      * One element buffered, awaiting timeout event from scheduler.
      *
      * @param timer the active timer
      * @param currentElem the buffered element
      * @param remaining number of elements already requested by downstream but not yet delivered, > 0
      */
    def awaitingTimeout(timer: Cancellable, currentElem: AnyRef, remaining: Long): State = state(
      request = (n, _) => awaitingTimeout(timer, currentElem, remaining ⊹ n),
      cancel = _ => { timer.cancel(); stopCancel(in) },
      onComplete = _ => upstreamCompletedAwaitingTimeout(timer, currentElem),
      onError = (e, _) => { timer.cancel(); stopError(e, out) },

      xEvent = {
        case StreamRunner.Timeout(_) =>
          out.onNext(currentElem)
          in.request(1)
          awaitingElement(remaining - 1)
      })

    /**
      * Upstream already completed, one element buffered, awaiting demand from downstream.
      *
      * @param currentElem the buffered element
      */
    def upstreamCompletedAwaitingDemand(currentElem: AnyRef) = state(
      request = (_, _) => {
        out.onNext(currentElem)
        stopComplete(out)
      },

      cancel = stopF)

    /**
      * Upstream already completed, demand from downstream present, one element buffered,
      * awaiting timeout event from scheduler.
      *
      * @param timer the active timer
      * @param currentElem the buffered element
      */
    def upstreamCompletedAwaitingTimeout(timer: Cancellable, currentElem: AnyRef): State = state(
      request = (_, _) => stay(),
      cancel = _ => { timer.cancel(); stop() },

      xEvent = {
        case StreamRunner.Timeout(_) =>
          out.onNext(currentElem)
          stopComplete(out)
      })

    def processElement(elem: AnyRef, rem: Long): State = {
      var funError: Throwable = null
      val elemCost = try costFn(elem) catch { case NonFatal(e) => { funError = e; 0 } }
      if (funError eq null) {
        requireState(rem > 0, s"`remaining` must be > 0")
        val delay = tokenBucket.offer(elemCost.toLong)
        if (delay > 0L) {
          val t = runner.scheduleTimeout(this, delay.nanos)
          awaitingTimeout(t, elem, rem)
        } else {
          out.onNext(elem)
          in.request(1)
          awaitingElement(rem - 1)
        }
      } else {
        in.cancel()
        stopError(funError, out)
      }
    }

    awaitingXStart()
  }
}
