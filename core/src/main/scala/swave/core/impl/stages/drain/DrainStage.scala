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

package swave.core.impl.stages.drain

import swave.core.PipeElem
import swave.core.impl.{ Inport, StartContext }
import swave.core.impl.stages.Stage

// format: OFF
private[swave] abstract class DrainStage extends Stage { this: PipeElem.Drain =>

  private[this] var _inputPipeElem: PipeElem.Basic = PipeElem.Unconnected
  def inputElem: PipeElem.Basic = _inputPipeElem

  protected def setInputElem(elem: PipeElem.Basic): Unit =
    _inputPipeElem = elem

  protected final def connectInAndStartWith(f: (StartContext, Inport) ⇒ State): Unit = {
    def ready(in: Inport) =
      fullState(name = "connectInAndStartWith:ready",

        onSubscribe = doubleOnSubscribe,

        start = ctx ⇒ {
          configureFrom(ctx)
          in.start(ctx)
          f(ctx, in)
        })

    initialState {
      fullState(name = "connectInAndStartWith",

        onSubscribe = in ⇒ {
          setInputElem(in.pipeElem)
          ready(in)
        })
    }
  }

  protected final def state(
    name: String,
    onSubscribe: Inport ⇒ State = unexpectedOnSubscribe,
    onNext: (AnyRef, Inport) ⇒ State = unexpectedOnNext,
    onComplete: Inport ⇒ State = unexpectedOnComplete,
    onError: (Throwable, Inport) ⇒ State = unexpectedOnError,
    extra: Stage.Extra = unexpectedExtra) =

    fullState(name, onSubscribe = onSubscribe, onNext = onNext, onComplete = onComplete, onError = onError, extra = extra)
}