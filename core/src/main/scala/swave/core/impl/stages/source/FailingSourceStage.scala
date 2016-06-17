/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.source

import swave.core.PipeElem
import swave.core.impl.Outport
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class FailingSourceStage(error: Throwable) extends SourceStage with PipeElem.Source.Repeat {

  def pipeElemType: String = "Stream.failing"
  def pipeElemParams: List[Any] = error :: Nil

  connectOutAndSealWith { (ctx, out) ⇒
    ctx.registerForXStart(this)
    awaitingXStart(out)
  }

  def awaitingXStart(out: Outport) = state(
    xStart = () => stopError(error, out))
}
