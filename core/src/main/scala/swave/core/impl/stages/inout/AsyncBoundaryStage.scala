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

  connectInOutAndSealWith { (ctx, inport, outport) ⇒
    val ins = inport.asInstanceOf[Stage]
    val outs = outport.asInstanceOf[Stage]
    ctx.registerForRunnerAssignment(ins, dispatcherId)
    ctx.registerForRunnerAssignment(outs) // fallback assignment of default StreamRunner
    running(ins, outs)
  }

  def running(ins: Stage, outs: Stage) = state(
    intercept = false,

    request = (n, _) ⇒ {
      ins.runner.enqueueRequest(ins, n.toLong)
      stay()
    },

    cancel = _ => {
      ins.runner.enqueueCancel(ins)
      stop()
    },

    onNext = (elem, _) ⇒ {
      outs.runner.enqueueOnNext(outs, elem)
      stay()
    },

    onComplete = _ => {
      outs.runner.enqueueOnComplete(outs)
      stop()
    },

    onError = (error, _) => {
      outs.runner.enqueueOnError(outs, error)
      stop(error)
    })
}
