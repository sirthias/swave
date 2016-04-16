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
import swave.core.impl.{ Inport, Outport, StartContext }

// format: OFF
private[inout] abstract class InOutStage extends PipeStage { this: PipeElem.InOut =>

  private[this] var _inputPipeElem: PipeElem.Basic = PipeElem.Unconnected
  private[this] var _outputPipeElem: PipeElem.Basic = PipeElem.Unconnected

  def inputElem = _inputPipeElem
  def outputElem =  _outputPipeElem

  protected final def connectInOutAndStartWith(f: (StartContext, Inport, Outport) ⇒ State): Unit = {
    def waitingForSubscribe(in: Inport) =
      fullState(name = "connectInOutAndStartWith:waitingForSubscribe",

        subscribe = out ⇒ {
          _outputPipeElem = out.pipeElem
          out.onSubscribe()
          ready(in, out)
        },

        onSubscribe = doubleOnSubscribe)

    def waitingForOnSubscribe(out: Outport) =
      fullState(name = "connectInOutAndStartWith:waitingForOnSubscribe",
        subscribe = doubleSubscribe,

        onSubscribe = in ⇒ {
          _inputPipeElem = in.pipeElem
          ready(in, out)
        })

    def ready(in: Inport, out: Outport) =
      fullState(name = "connectInOutAndStartWith:ready",
        subscribe = doubleSubscribe,
        onSubscribe = doubleOnSubscribe,

        start = ctx ⇒ {
          configureFrom(ctx)
          in.start(ctx)
          out.start(ctx)
          f(ctx, in, out)
        })

    initialState {
      fullState(name = "connectInOutAndStartWith",

        onSubscribe = in ⇒ {
          _inputPipeElem = in.pipeElem
          waitingForSubscribe(in)
        },

        subscribe = out ⇒ {
          _outputPipeElem = out.pipeElem
          out.onSubscribe()
          waitingForOnSubscribe(out)
        })
    }
  }
}