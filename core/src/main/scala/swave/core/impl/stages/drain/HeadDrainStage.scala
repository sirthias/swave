/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.drain

import scala.concurrent.Promise
import swave.core.macros.StageImpl
import swave.core.PipeElem
import swave.core.impl.Inport

// format: OFF
@StageImpl
private[core] final class HeadDrainStage(headPromise: Promise[AnyRef]) extends DrainStage with PipeElem.Drain.Head {

  def pipeElemType: String = "Drain.head"
  def pipeElemParams: List[Any] = Nil

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
      headPromise.failure(new NoSuchElementException())
      stop()
    },

    onError = (e, _) ⇒ {
      headPromise.failure(e)
      stop(e)
    })
}
