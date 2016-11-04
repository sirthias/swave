/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.fanout

import swave.core.PipeElem
import swave.core.impl.Outport

private[core] final class FirstAvailableStage(eagerCancel: Boolean)
    extends FanOutStage
    with PipeElem.FanOut.FirstAvailable {

  def pipeElemType: String      = "fanOutFirstAvailable"
  def pipeElemParams: List[Any] = eagerCancel :: Nil

  type OutportCtx = FanOutStage.SimpleOutportContext
  protected def createOutportCtx(out: Outport, tail: OutportCtx): OutportCtx =
    new FanOutStage.SimpleOutportContext(out, tail)
}
