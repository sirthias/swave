/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.spout

import swave.core.Stage
import swave.core.impl.Outport
import swave.core.impl.stages.SpoutStage
import swave.core.macros.StageImplementation

// format: OFF
@StageImplementation
private[core] final class FailingSpoutStage(error: Throwable, eager: Boolean) extends SpoutStage {

  def kind = Stage.Kind.Spout.Failing(error, eager)

  connectOutAndSealWith { (ctx, out) ⇒
    if (eager) {
      ctx.registerForXStart(this)
      awaitingXStart(out)
    } else awaitingRequest(out)
  }

  def awaitingXStart(out: Outport) = state(
    xStart = () => stopError(error, out))

  def awaitingRequest(out: Outport) = state(
    request = (_, _) => stopError(error, out),
    cancel = _ => stop())
}
