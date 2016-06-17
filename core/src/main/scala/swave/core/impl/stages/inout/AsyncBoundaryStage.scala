/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import swave.core.PipeElem
import swave.core.impl.stages.Stage
import swave.core.impl.{ Inport, Outport }
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class AsyncBoundaryStage(dispatcherId: String) extends InOutStage with PipeElem.InOut.AsyncBoundary {

  def pipeElemType: String = "asyncBoundary"
  def pipeElemParams: List[Any] = dispatcherId :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    val ins = in.asInstanceOf[Stage]
    val outs = out.asInstanceOf[Stage]
    ctx.registerForRunnerAssignment(ins, dispatcherId)
    ctx.registerForRunnerAssignment(outs) // fallback assignment of default StreamRunner
    running(ins, outs)
  }

  def running(inStage: Stage, outStage: Stage) = state(
    intercept = false,

    request = (n, _) ⇒ {
      inStage.runner.enqueueRequest(inStage, n.toLong)
      stay()
    },

    cancel = _ => {
      inStage.runner.enqueueCancel(inStage)
      stop()
    },

    onNext = (elem, _) ⇒ {
      outStage.runner.enqueueOnNext(outStage, elem)
      stay()
    },

    onComplete = _ => {
      outStage.runner.enqueueOnComplete(outStage)
      stop()
    },

    onError = (error, _) => {
      outStage.runner.enqueueOnError(outStage, error)
      stop()
    })
}
