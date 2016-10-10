/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.concurrent.duration._
import swave.core.impl.{Inport, Outport, StreamRunner}
import swave.core.{Cancellable, PipeElem, StreamTimeoutException}
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class WithIdleTimeoutStage(timeout: FiniteDuration) extends InOutStage
  with PipeElem.InOut.WithIdleTimeout {

  requireArg(timeout > Duration.Zero, "The `timeout` must be > 0")

  def pipeElemType: String = "withIdleTimeout"
  def pipeElemParams: List[Any] = timeout :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForRunnerAssignment(this)
    running(in, out)
  }

  def running(in: Inport, out: Outport) = {

    def awaitingDemand() = state(
      request = (n, _) => {
        in.request(n.toLong)
        active(runner.scheduleTimeout(this, timeout), n.toLong)
      },

      cancel = stopCancelF(in),
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    def active(timer: Cancellable, remaining: Long): State = state(
      request = (n, _) => {
        in.request(n.toLong)
        active(timer, remaining ⊹ n)
      },

      cancel = _ => {
        timer.cancel()
        stopCancel(in)
      },

      onNext = (elem, _) => {
        timer.cancel()
        out.onNext(elem)
        if (remaining > 1) active(runner.scheduleTimeout(this, timeout), remaining - 1)
        else awaitingDemand()
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
        case StreamRunner.Timeout(t) =>
          if (t eq timer) {
            in.cancel()
            val e = new StreamTimeoutException(s"No elements passed in the last $timeout")
            stopError(e, out)
          } else stay()
      })

    awaitingDemand()
  }

}
