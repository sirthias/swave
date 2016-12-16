/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import swave.core.Stage
import swave.core.impl.{Inport, Outport}
import swave.core.impl.stages.InOutStage
import swave.core.macros.StageImplementation

// format: OFF
@StageImplementation
private[core] final class AsyncDispatcherStage(dispatcherId: String) extends InOutStage {

  def kind = Stage.Kind.InOut.AsyncDispatcher(dispatcherId)

  connectInOutAndSealWith { (in, out) ⇒
    region.impl.requestDispatcherAssignment(dispatcherId)
    running(in, out)
  }

  def running(in: Inport, out: Outport) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),
    onNext = onNextF(out),
    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
