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

import scala.annotation.compileTimeOnly
import swave.core.impl.{ Outport, Inport, RunContext }
import swave.core.PipeElem
import swave.core.impl.stages.Stage

// format: OFF
private[core] abstract class InOutStage extends Stage { this: PipeElem.InOut =>

  protected final var _inputPipeElem: PipeElem.Basic = PipeElem.Unconnected
  protected final var _outputPipeElem: PipeElem.Basic = PipeElem.Unconnected

  final def inputElem = _inputPipeElem
  final def outputElem =  _outputPipeElem

  @compileTimeOnly("Unresolved `connectInOutAndSealWith` call")
  protected final def connectInOutAndSealWith(f: (RunContext, Inport, Outport) ⇒ State): Unit = ()
}