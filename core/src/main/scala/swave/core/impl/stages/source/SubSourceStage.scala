/*
 * Copyright © 2016 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swave.core.impl.stages.source

import swave.core.PipeElem
import swave.core.impl.{ Inport, Outport, RunContext }
import swave.core.macros.StageImpl
import SubSourceStage._

// format: OFF
@StageImpl
private[core] final class SubSourceStage(ctx: RunContext, in: Inport) extends SourceStage
  with PipeElem.Source.Sub {

  def pipeElemType: String = "sub"
  def pipeElemParams: List[Any] = in :: Nil

  initialState(awaitingSubscribe(Termination.None))

  def awaitingSubscribe(termination: Termination): State = state(
    subscribe = from ⇒ {
      _outputPipeElem = from.pipeElem
      from.onSubscribe()
      ready(from, termination)
    },

    onComplete = _ => awaitingSubscribe(Termination.Completed),
    onError = (e, _) => awaitingSubscribe(Termination.Error(e)))

  def ready(out: Outport, termination: Termination): State = state(
    xSeal = subCtx ⇒ {
      ctx.attach(subCtx)
      configureFrom(ctx.env)
      out.xSeal(ctx)
      termination match {
        case Termination.None => running(out)
        case _ =>
          ctx.registerForXStart(this)
          awaitingXStart(out, termination)
      }
    },

    onComplete = _ => ready(out, Termination.Completed),
    onError = (e, _) => ready(out, Termination.Error(e)))

  def awaitingXStart(out: Outport, termination: Termination) = state(
    xStart = () => {
      termination match {
        case Termination.Error(e) => stopError(e, out)
        case _ => stopComplete(out)
      }
    })

  def running(out: Outport) = state(
    request = (n, _) ⇒ {
      in.request(n.toLong)
      stay()
    },

    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      out.onNext(elem)
      stay()
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}

private[core] object SubSourceStage {

  private abstract class Termination
  private object Termination {
    case object None extends Termination
    case object Completed extends Termination
    final case class Error(e: Throwable) extends Termination
  }
}

