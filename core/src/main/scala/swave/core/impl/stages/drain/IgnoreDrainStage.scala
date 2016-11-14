/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.drain

import scala.concurrent.Promise
import swave.core.Stage
import swave.core.impl.Inport
import swave.core.macros.StageImplementation

// format: OFF
@StageImplementation
private[core] final class IgnoreDrainStage(terminationPromise: Promise[Unit])
  extends DrainStage {

  def kind = Stage.Kind.Drain.Ignore(terminationPromise)

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
    onNext = (_, _) ⇒ stay(),

    onComplete = _ ⇒ {
      terminationPromise.success(())
      stop()
    },

    onError = (e, _) ⇒ {
      terminationPromise.failure(e)
      stop(e)
    })
}
