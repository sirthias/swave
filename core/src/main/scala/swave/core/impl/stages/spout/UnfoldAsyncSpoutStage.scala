/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.spout

import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.util.control.NonFatal
import swave.core.Stage
import swave.core.Spout.Unfolding
import swave.core.impl.stages.SpoutStage
import swave.core.impl.{CallingThreadExecutionContext, Outport}
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImplementation
private[core] final class UnfoldAsyncSpoutStage(zero: AnyRef, f: AnyRef => Future[Unfolding[AnyRef, AnyRef]])
  extends SpoutStage {

  def kind = Stage.Kind.Spout.UnfoldAsync(zero, f)

  connectOutAndSealWith { (ctx, out) ⇒
    ctx.registerForRunnerAssignment(this)
    awaitingDemand(out, zero)
  }

  /**
    * Active with no demand from downstream.
    *
    * @param out the active downstream
    * @param s   the current unfolding state
    */
  def awaitingDemand(out: Outport, s: AnyRef): State = state(
    request = (n, _) ⇒ handleDemand(out, s, n.toLong),
    cancel = stopF)

  /**
    * Active with pending demand from downstream and a future onCompletion handler scheduled.
    *
    * @param out       the active downstream
    * @param remaining number of elements already requested by downstream but not yet delivered, > 0
    */
  def awaitingUnfolding(out: Outport, remaining: Long): State = {
    requireState(remaining > 0)
    state(
      request = (n, _) ⇒ awaitingUnfolding(out, remaining ⊹ n),
      cancel = stopF,

      xEvent = {
        case Success(Unfolding.Emit(elem: AnyRef, next: AnyRef)) =>
          out.onNext(elem)
          if (remaining > 1) handleDemand(out, next, remaining - 1)
          else awaitingDemand(out, next)

        case Success(Unfolding.EmitFinal(elem: AnyRef)) =>
          out.onNext(elem)
          stopComplete(out)

        case Success(Unfolding.Complete) =>
          stopComplete(out)

        case Failure(e) =>
          stopError(e, out)
      })
  }

  private def handleDemand(out: Outport, s: AnyRef, remaining: Long): State = {
    var funError: Throwable = null
    val unfoldingFuture = try f(s) catch { case NonFatal(e) => { funError = e; null } }
    if (funError eq null) {
      // when the future completes we want to receive an XEvent,
      // since enqueueing is extremely lightweight we can do it directly on the calling thread
      unfoldingFuture.onComplete(runner.enqueueXEvent(this, _))(CallingThreadExecutionContext)
      awaitingUnfolding(out, remaining)
    } else stopError(funError, out)
  }
}
