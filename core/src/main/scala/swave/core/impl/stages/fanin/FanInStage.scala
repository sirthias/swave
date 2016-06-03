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

package swave.core.impl.stages.fanin

import scala.annotation.compileTimeOnly
import scala.collection.mutable.ListBuffer
import swave.core.PipeElem
import swave.core.impl.stages.Stage
import swave.core.impl.{ Outport, RunContext, InportList }

// format: OFF
private[core] abstract class FanInStage extends Stage { this: PipeElem.FanIn =>

  protected final var _outputPipeElem: PipeElem.Basic = PipeElem.Unconnected
  protected final var _inputElems = InportList.empty

  final def outputElem = _outputPipeElem
  final def inputElems = {
    val buf = new ListBuffer[PipeElem.Basic]
    for (i <- _inputElems) {
      buf += i.in.asInstanceOf[PipeElem.Basic]
      ()
    }
    buf.result()
  }

  @compileTimeOnly("Unresolved `connectFanInAndSealWith` call")
  protected final def connectFanInAndSealWith(subs: InportList)(f: (RunContext, Outport) ⇒ State): Unit = ()
}