/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.drain

import scala.util.control.NonFatal
import scala.concurrent.Promise
import swave.core.macros.StageImpl
import swave.core.PipeElem
import swave.core.impl.Inport

// format: OFF
@StageImpl
private[core] final class ForeachDrainStage(
    callback: AnyRef ⇒ Unit,
    terminationPromise: Promise[Unit]) extends DrainStage with PipeElem.Drain.Foreach {

  def pipeElemType: String = "Drain.foreach"
  def pipeElemParams: List[Any] = callback :: terminationPromise :: Nil

  connectInAndSealWith { (ctx, in) ⇒
    registerForRunnerAssignmentIfRequired(ctx)
    ctx.registerForXStart(this)
    awaitingXStart(in)
  }

  /**
    * @param in the active upstream
    */
  def awaitingXStart(in: Inport) = state(
    xStart = () => {
      in.request(Long.MaxValue)
      running(in)
    })

  /**
    * @param in the active upstream
    */
  def running(in: Inport) = state(
    onNext = (elem, _) ⇒ {
      try {
        callback(elem)
        stay()
      } catch {
        case NonFatal(e) =>
          terminationPromise.failure(e)
          stopCancel(in)
      }
    },

    onComplete = _ ⇒ {
      terminationPromise.success(())
      stop()
    },

    onError = (e, _) ⇒ {
      terminationPromise.failure(e)
      stop(e)
    })
}
