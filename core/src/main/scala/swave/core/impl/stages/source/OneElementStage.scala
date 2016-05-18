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
import swave.core.impl.Outport
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class OneElementStage(element: AnyRef) extends SourceStage with PipeElem.Source.OneElement {

  def pipeElemType: String = "Stream.one"
  def pipeElemParams: List[Any] = element :: Nil

  connectOutAndSealWith { (ctx, out) ⇒ awaitingRequest(out) }

  def awaitingRequest(out: Outport): State = state(
    request = (_, _) ⇒ {
      out.onNext(element)
      stopComplete(out)
    },

    cancel = stopF)
}

