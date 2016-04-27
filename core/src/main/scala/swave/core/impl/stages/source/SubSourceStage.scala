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

// format: OFF
private[core] final class SubSourceStage(runContext: RunContext, in: Inport) extends SourceStage
  with PipeElem.Source.Sub {

  import SubSourceStage._

  def pipeElemType: String = "sub"
  def pipeElemParams: List[Any] = in :: Nil

  initialState(waitingForSubscribe(Termination.None))

  def waitingForSubscribe(termination: Termination): State =
    fullState(name = "waitingForSubscribe",

      subscribe = out ⇒ {
        setOutputElem(out.pipeElem)
        out.onSubscribe()
        ready(out, termination)
      },

      onComplete = _ => waitingForSubscribe(Termination.Completed),
      onError = (e, _) => waitingForSubscribe(Termination.Error(e)))


  def ready(out: Outport, termination: Termination): State =
    fullState(name = "ready",

      start = ctx ⇒ {
        configureFrom(ctx)
        out.start(ctx)
        ctx.setRunContext(runContext)
        termination match {
          case Termination.None => running(out)
          case Termination.Completed => stopComplete(out)
          case Termination.Error(e) => stopError(e, out)
        }
      },

      onComplete = _ => ready(out, Termination.Completed),
      onError = (e, _) => ready(out, Termination.Error(e)))


  def running(out: Outport) =
    fullState(name = "running",

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

