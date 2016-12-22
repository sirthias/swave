/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import swave.core.Stage
import swave.core.impl.stages.{InOutStage, StageImpl}
import swave.core.impl.{Inport, Outport}
import swave.core.macros._

// format: OFF
@StageImplementation
private[core] final class AsyncBoundaryStage(dispatcherId: String) extends InOutStage {

  def kind = Stage.Kind.InOut.AsyncBoundary(dispatcherId)

  initialState(awaitingSubscribeOrOnSubscribe())

  def awaitingSubscribeOrOnSubscribe() = state(
    intercept = false,

    onSubscribe = from ⇒ {
      _inputStages = from.stageImpl :: Nil
      awaitingSubscribe(from)
    },

    subscribe = from ⇒ {
      _outputStages = from.stageImpl :: Nil
      from.onSubscribe()
      awaitingOnSubscribe(from)
    })

  def awaitingSubscribe(in: Inport) = state(
    intercept = false,

    subscribe = from ⇒ {
      _outputStages = from.stageImpl :: Nil
      from.onSubscribe()
      ready(in, from)
    })

  def awaitingOnSubscribe(out: Outport) = state(
    intercept = false,

    onSubscribe = from ⇒ {
      _inputStages = from.stageImpl :: Nil
      ready(from, out)
    })

  def ready(in: Inport, out: Outport) = state(
    intercept = false,

    xSeal = () ⇒ {
      val inp = in.stageImpl
      val outp = out.stageImpl

      def completeSealing() = {
        region.impl.requestDispatcherAssignment(dispatcherId)
        running(inp, outp)
      }

      if (inp.isSealed) {
        requireState(region eq inp.region)
        if (outp.isSealed) requireState(region ne outp.region)
        else region.runContext.impl.registerForSealing(outp)
        completeSealing()
      } else if (outp.isSealed) {
        if (region eq outp.region) {
          region.runContext.impl.registerForSealing(this)
          resetRegion()
          stay()
        } else {
          inp.xSeal(region)
          completeSealing()
        }
      } else {
        region.runContext.impl.registerForSealing(outp)
        inp.xSeal(region)
        completeSealing()
      }
    })

  def running(inp: StageImpl, outp: StageImpl) = state(
    intercept = false,

    request = (n, _) ⇒ {
      region.impl.enqueueRequest(inp, n.toLong)
      stay()
    },

    cancel = from => {
      if (from ne this) {
        // if we are called directly from downstream we are not on the right thread
        // and must not mutate our state in any way
        region.impl.enqueueCancel(this)
        stay()
      } else stopCancel(inp) // once we are on the right thread we can cancel and stop normally
    },

    onNext = (elem, _) ⇒ {
      outp.region.impl.enqueueOnNext(outp, elem)
      stay()
    },

    onComplete = _ => {
      outp.region.impl.enqueueOnComplete(outp)
      stop()
    },

    onError = (error, _) => {
      outp.region.impl.enqueueOnError(outp, error)
      stop(error)
    })
}
