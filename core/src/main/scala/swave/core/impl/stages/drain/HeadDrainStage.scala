/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.drain

import scala.concurrent.Promise
import swave.core.macros.StageImplementation
import swave.core.Stage
import swave.core.impl.Inport

// format: OFF
@StageImplementation
private[core] final class HeadDrainStage(headPromise: Promise[AnyRef]) extends DrainStage {

  def kind = Stage.Kind.Drain.Head

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
      in.request(1)
      receiveOne(in)
    })

  /**
    * @param in the active upstream
    */
  def receiveOne(in: Inport) = state(
    onNext = (elem, _) ⇒ {
      headPromise.success(elem)
      stopCancel(in)
    },

    onComplete = _ ⇒ {
      headPromise.failure(new NoSuchElementException)
      stop()
    },

    onError = (e, _) ⇒ {
      headPromise.failure(e)
      stop(e)
    })
}
