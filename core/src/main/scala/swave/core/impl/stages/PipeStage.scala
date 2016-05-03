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

package swave.core.impl.stages

import swave.core.PipeElem
import swave.core.impl.{ Inport, Outport }

private[core] abstract class PipeStage extends Stage { this: PipeElem.Basic ⇒

  protected final def state(
    name: String,
    subscribe: Outport ⇒ State = unexpectedSubscribe,
    request: (Int, Outport) ⇒ State = unexpectedRequestInt,
    cancel: Outport ⇒ State = unexpectedCancel,
    onSubscribe: Inport ⇒ State = unexpectedOnSubscribe,
    onNext: (AnyRef, Inport) ⇒ State = unexpectedOnNext,
    onComplete: Inport ⇒ State = unexpectedOnComplete,
    onError: (Throwable, Inport) ⇒ State = unexpectedOnError,
    extra: Stage.ExtraSignalHandler = unexpectedExtra) =

    fullState(name = name, subscribe = subscribe, request = request, cancel = cancel, onSubscribe = onSubscribe,
      onNext = onNext, onComplete = onComplete, onError = onError, onExtraSignal = extra)
}