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
import swave.core.impl.{ RunContext, Outport }
import swave.core.impl.stages.Stage

// format: OFF
private[swave] abstract class SourceStage extends Stage { this: PipeElem.Source ⇒

  private[this] var _outputPipeElem: PipeElem.Basic = PipeElem.Unconnected
  def outputElem: PipeElem.Basic = _outputPipeElem

  protected def setOutputElem(elem: PipeElem.Basic): Unit =
    _outputPipeElem = elem

  protected final def state(
    name: String,
    subscribe: Outport ⇒ State = null,
    request: (Int, Outport) ⇒ State = null,
    cancel: Outport ⇒ State = null,
    xSeal: RunContext => State = null,
    xStart: () => State= null) =
    fullState(name, subscribe = subscribe, request = request, cancel = cancel, xSeal = xSeal, xStart = xStart)

  protected final def connectOutAndStartWith(f: (RunContext, Outport) ⇒ State): Unit = {
    def awaitingSubscribe =
      fullState(
        name = "connectOutAndStartWith:awaitingSubscribe",
        interceptWhileHandling = false,

        subscribe = out ⇒ {
          setOutputElem(out.pipeElem)
          out.onSubscribe()
          ready(out)
        })

    def ready(out: Outport) =
      fullState(
        name = "connectOutAndStartWith:ready",

        xSeal = ctx ⇒ {
          configureFrom(ctx.env)
          out.xSeal(ctx)
          f(ctx, out)
        })

    initialState(awaitingSubscribe)
  }
}

