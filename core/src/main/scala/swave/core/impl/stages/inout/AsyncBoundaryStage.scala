/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import swave.core.Stage
import swave.core.impl.stages.{InOutStage, StageImpl}
import swave.core.impl.{Inport, Outport, StreamRunner}
import swave.core.macros.StageImplementation

// format: OFF
@StageImplementation
private[core] final class AsyncBoundaryStage(dispatcherId: String) extends InOutStage {

  def kind = Stage.Kind.InOut.AsyncBoundary(dispatcherId)

  connectInOutAndSealWith { (ctx, inport, outport) ⇒
    val inp = inport.stageImpl
    val outp = outport.stageImpl
    ctx.registerRunnerAssignment(StreamRunner.Assignment(inp, dispatcherId))
    ctx.registerRunnerAssignment(StreamRunner.Assignment.Default(outp))
    running(inp, outp)
  }

  def running(inp: StageImpl, outp: StageImpl) = state(
    intercept = false,

    request = (n, _) ⇒ {
      inp.runner.enqueueRequest(inp, n.toLong)
      stay()
    },

    cancel = _ => {
      inp.runner.enqueueCancel(inp)
      stop()
    },

    onNext = (elem, _) ⇒ {
      outp.runner.enqueueOnNext(outp, elem)
      stay()
    },

    onComplete = _ => {
      outp.runner.enqueueOnComplete(outp)
      stop()
    },

    onError = (error, _) => {
      outp.runner.enqueueOnError(outp, error)
      stop(error)
    })
}
