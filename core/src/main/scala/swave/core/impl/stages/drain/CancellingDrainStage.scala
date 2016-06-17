/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.drain

import swave.core.PipeElem
import swave.core.impl.Inport
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class CancellingDrainStage extends DrainStage with PipeElem.Drain.Cancelling {

  def pipeElemType: String = "Drain.cancelling"
  def pipeElemParams: List[Any] = Nil

  connectInAndSealWith { (ctx, in) ⇒
    registerForRunnerAssignmentIfRequired(ctx)
    ctx.registerForXStart(this)
    awaitingXStart(in)
  }

  /**
   * @param in the active upstream
   */
  def awaitingXStart(in: Inport) =
    state(xStart = () => stopCancel(in))
}
