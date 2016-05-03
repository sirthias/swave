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
import swave.core.impl.{ StartContext, Outport }
import swave.core.impl.stages.Stage

// format: OFF
private[swave] abstract class SourceStage extends Stage { this: PipeElem.Source ⇒

  private[this] var _outputPipeElem: PipeElem.Basic = PipeElem.Unconnected
  def outputElem: PipeElem.Basic = _outputPipeElem

  protected def setOutputElem(elem: PipeElem.Basic): Unit =
    _outputPipeElem = elem

  protected final def state(
    name: String,
    subscribe: Outport ⇒ State = unexpectedSubscribe,
    request: (Int, Outport) ⇒ State = unexpectedRequestInt,
    cancel: Outport ⇒ State = unexpectedCancel,
    extra: Stage.ExtraSignalHandler = unexpectedExtra) =

    fullState(name, subscribe = subscribe, request = request, cancel = cancel, onExtraSignal = extra)

  protected final def connectOutAndStartWith(f: (StartContext, Outport) ⇒ State): Unit = {
    def ready(out: Outport) =
      fullState(
        name = "connectOutAndStartWith:ready",

        subscribe = doubleSubscribe,

        start = ctx ⇒ {
          configureFrom(ctx.env)
          out.start(ctx)
          f(ctx, out)
        })

    initialState {
      fullState(
        name = "connectOutAndStartWith",

        subscribe = out ⇒ {
          setOutputElem(out.pipeElem)
          out.onSubscribe()
          ready(out)
        })
    }
  }
}

