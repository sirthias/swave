/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import swave.core.PipeElem
import swave.core.impl.{Inport, Outport}
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class NopStage extends InOutStage with PipeElem.InOut.Nop {

  def pipeElemType: String = "nop"
  def pipeElemParams: List[Any] = Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),
    onNext = onNextF(out),
    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
