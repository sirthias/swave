/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.concurrent.duration._
import scala.collection.immutable.VectorBuilder
import swave.core.impl.{Inport, Outport, StreamRunner}
import swave.core.{Cancellable, PipeElem}
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class GroupedWithinStage(maxSize: Int, duration: FiniteDuration)
  extends InOutStage with PipeElem.InOut.GroupedWithin {

  requireArg(maxSize > 0, "`maxSize` must be > 0")
  requireArg(duration > Duration.Zero, "`duration` must be > 0")

  def pipeElemType: String = "groupedWithin"
  def pipeElemParams: List[Any] = maxSize :: duration :: Nil

  private[this] val builder = new VectorBuilder[AnyRef]
  private[this] var builderSize = 0 // TODO: remove when https://issues.scala-lang.org/browse/SI-9904 is fixed

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForRunnerAssignment(this)
    ctx.registerForXStart(this)
    running(in, out)
  }

  def running(in: Inport, out: Outport) = {

    def awaitingXStart() = state(
      xStart = () => {
        val timer = runner.scheduleTimeout(this, duration)
        in.request(maxSize.toLong)
        awaitingElem(timer, 0L)
      })

    /**
      * Awaiting elements from upstream. At least one element pending from upstream.
      *
      * @param timer the timer for the current period
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingElem(timer: Cancellable, mainRemaining: Long): State = state(
      request = (n, _) => awaitingElem(timer, mainRemaining ⊹ n),

      cancel = _ => {
        timer.cancel()
        stopCancel(in)
      },

      onNext = (elem, _) ⇒ {
        builder += elem
        builderSize += 1
        if (builderSize == maxSize) {
          timer.cancel()
          if (mainRemaining > 0) emitCurrentGroup(mainRemaining) else awaitingDemand()
        } else awaitingElem(timer, mainRemaining)
      },

      onComplete = _ => {
        timer.cancel()
        if (builderSize > 0) out.onNext(builder.result())
        stopComplete(out)
      },

      onError = (e, _) => {
        timer.cancel()
        stopError(e, out)
      },

      xEvent = {
        case StreamRunner.Timeout(t) if t eq timer =>
          if (builderSize > 0) {
            if (mainRemaining > 0) emitCurrentGroup(mainRemaining) else awaitingDemand()
          } else awaitingKickerElem(mainRemaining)
        case StreamRunner.Timeout(_) => stay() // old timeout whose delivery raced with the cancel we already did
      })

    /**
      * Next group ready to be sent, awaiting demand from downstream. Builder non-empty.
      * Potentially elements pending from upstream.
      */
    def awaitingDemand(): State = {
      requireState(builderSize > 0)
      state(
        request = (n, _) => emitCurrentGroup(n.toLong),
        cancel = stopCancelF(in),

        onNext = (elem, _) ⇒ {
          builder += elem
          builderSize += 1
          awaitingDemand()
        },

        onComplete = _ => awaitingDemandUpstreamGone(),
        onError = stopErrorF(out),
        xEvent = { case StreamRunner.Timeout(_) => stay() })
    }

    /**
      * Time period expired without reception of any element. Builder empty. `maxSize` elements pending.
      * Awaiting the next element, which is to be emitted immediately in a single-element group.
      *
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingKickerElem(mainRemaining: Long): State = {
      requireState(builderSize == 0)
      state(
        request = (n, _) => awaitingKickerElem(mainRemaining ⊹ n),
        cancel = stopCancelF(in),

        onNext = (elem, _) ⇒ {
          builder += elem
          builderSize += 1
          if (mainRemaining > 0) emitCurrentGroup(mainRemaining) else awaitingDemand()
        },

        onComplete = stopCompleteF(out),
        onError = stopErrorF(out),
        xEvent = { case StreamRunner.Timeout(_) => stay() })
    }

    /**
      * Next group ready to be sent, awaiting demand from downstream. Builder non-empty.
      * Upstream already completed.
      */
    def awaitingDemandUpstreamGone(): State = state(
      request = (_, _) => {
        val group = builder.result()
        requireState(group.nonEmpty)
        out.onNext(group)
        stopComplete(out)
      },

      cancel = stopF,
      xEvent = { case StreamRunner.Timeout(_) => stay() })

    def emitCurrentGroup(mainRemaining: Long): State = {
      val group = builder.result()
      requireState(group.nonEmpty)
      out.onNext(group)
      builder.clear()
      builderSize = 0
      val newTimer = runner.scheduleTimeout(this, duration)
      in.request(group.size.toLong) // bring pending element count back up to maxSize
      awaitingElem(newTimer, mainRemaining - 1)
    }

    awaitingXStart()
  }
}
