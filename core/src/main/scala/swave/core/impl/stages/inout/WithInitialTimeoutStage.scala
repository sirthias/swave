/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.concurrent.duration._
import swave.core.impl.stages.InOutStage
import swave.core.impl.{Inport, Outport, StreamRunner}
import swave.core.macros._
import swave.core.{Cancellable, Stage, StreamTimeoutException}

// format: OFF
@StageImplementation
private[core] final class WithInitialTimeoutStage(timeout: FiniteDuration) extends InOutStage {

  requireArg(timeout > Duration.Zero, "The `timeout` must be > 0")

  def kind = Stage.Kind.InOut.WithInitialTimeout(timeout)

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerRunnerAssignment(StreamRunner.Assignment.Default(this))
    running(in, out)
  }

  def running(in: Inport, out: Outport) = {

    def awaitingFirstDemand() = state(
      request = (n, _) => {
        in.request(n.toLong)
        awaitingFirstElem(runner.scheduleTimeout(this, timeout))
      },

      cancel = stopCancelF(in),
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    def awaitingFirstElem(timer: Cancellable) = state(
      request = requestF(in),

      cancel = _ => {
        timer.cancel()
        stopCancel(in)
      },

      onNext = (elem, _) => {
        timer.cancel()
        out.onNext(elem)
        forwarding()
      },

      onComplete = _ => {
        timer.cancel()
        stopComplete(out)
      },

      onError = (e, _) => {
        timer.cancel()
        stopError(e, out)
      },

      xEvent = {
        case StreamRunner.Timeout(_) =>
          val e = new StreamTimeoutException(s"The first element was not received within $timeout")
          stopError(e, out)
      })

    def forwarding() = state(
      intercept = false,

      request = requestF(in),
      cancel = stopCancelF(in),
      onNext = onNextF(out),
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out),
      xEvent = { case StreamRunner.Timeout(_) => stay() })

    awaitingFirstDemand()
  }
}
