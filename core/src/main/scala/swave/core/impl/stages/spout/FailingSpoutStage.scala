/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.spout

import swave.core.PipeElem
import swave.core.impl.Outport
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class FailingSpoutStage(error: Throwable, eager: Boolean)
  extends SpoutStage with PipeElem.Spout.Failing {

  def pipeElemType: String = "Spout.failing"
  def pipeElemParams: List[Any] = error :: Nil

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
