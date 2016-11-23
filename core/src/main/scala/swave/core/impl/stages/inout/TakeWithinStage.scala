/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.concurrent.duration._
import swave.core.impl.stages.InOutStage
import swave.core.{Cancellable, Stage}
import swave.core.impl.{Inport, Outport, StreamRunner}
import swave.core.macros._

// format: OFF
@StageImplementation
private[core] final class TakeWithinStage(duration: FiniteDuration) extends InOutStage {

  requireArg(duration >= Duration.Zero, "The `duration` must be non-negative")

  def kind = Stage.Kind.InOut.TakeWithin(duration)

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerRunnerAssignment(StreamRunner.Assignment.Default(this))
    ctx.registerForXStart(this)
    awaitingXStart(in, out)
  }

  def awaitingXStart(in: Inport, out: Outport) = state(
    xStart = () => running(in, out, runner.scheduleTimeout(this, duration)))

  def running(in: Inport, out: Outport, timer: Cancellable) = state(
    intercept = false,

    request = requestF(in),

    cancel = _ => {
      timer.cancel()
      stopCancel(in)
    },

    onNext = (elem, _) ⇒ {
      out.onNext(elem)
      stay()
    },

    onComplete = _ => {
      timer.cancel()
      stopComplete(out)
    },

    onError = (e, _) => {
      timer.cancel()
      stopError(e, out)
    },

    xEvent = { case StreamRunner.Timeout(t) if t eq timer => stopComplete(out) })
}
