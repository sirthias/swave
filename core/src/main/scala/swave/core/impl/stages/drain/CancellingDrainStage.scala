/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.drain

import swave.core.Stage
import swave.core.impl.Inport
import swave.core.impl.stages.DrainStage
import swave.core.macros.StageImplementation

// format: OFF
@StageImplementation
private[core] final class CancellingDrainStage extends DrainStage {

  def kind = Stage.Kind.Drain.Cancelling

  connectInAndSealWith { (ctx, in) ⇒
    ctx.registerForXStart(this)
    awaitingXStart(in)
  }

  /**
    * @param in the active upstream
    */
  def awaitingXStart(in: Inport) =
    state(xStart = () => stopCancel(in))
}
