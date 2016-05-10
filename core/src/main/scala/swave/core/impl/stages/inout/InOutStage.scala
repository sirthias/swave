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

package swave.core.impl.stages.inout

import swave.core.PipeElem
import swave.core.impl.stages.PipeStage
import swave.core.impl.{ Inport, Outport, RunContext }

// format: OFF
private[inout] abstract class InOutStage extends PipeStage { this: PipeElem.InOut =>

  private[this] var _inputPipeElem: PipeElem.Basic = PipeElem.Unconnected
  private[this] var _outputPipeElem: PipeElem.Basic = PipeElem.Unconnected

  def inputElem = _inputPipeElem
  def outputElem =  _outputPipeElem

  protected final def connectInOutAndStartWith(f: (RunContext, Inport, Outport) ⇒ State): Unit = {
    def awaitingSubscribeOrOnSubscribe =
      fullState(name = "connectInOutAndStartWith:awaitingSubscribeOrOnSubscribe",
        interceptWhileHandling = false,

        onSubscribe = in ⇒ {
          _inputPipeElem = in.pipeElem
          awaitingSubscribe(in)
        },

        subscribe = out ⇒ {
          _outputPipeElem = out.pipeElem
          out.onSubscribe()
          awaitingOnSubscribe(out)
        })

    def awaitingSubscribe(in: Inport) =
      fullState(name = "connectInOutAndStartWith:awaitingSubscribe",
        interceptWhileHandling = false,

        subscribe = out ⇒ {
          _outputPipeElem = out.pipeElem
          out.onSubscribe()
          ready(in, out)
        })

    def awaitingOnSubscribe(out: Outport) =
      fullState(name = "connectInOutAndStartWith:awaitingOnSubscribe",
        interceptWhileHandling = false,

        onSubscribe = in ⇒ {
          _inputPipeElem = in.pipeElem
          ready(in, out)
        })

    def ready(in: Inport, out: Outport) =
      fullState(name = "connectInOutAndStartWith:ready",

        xSeal = ctx ⇒ {
          configureFrom(ctx.env)
          in.xSeal(ctx)
          out.xSeal(ctx)
          f(ctx, in, out)
        })

    initialState(awaitingSubscribeOrOnSubscribe)
  }
}