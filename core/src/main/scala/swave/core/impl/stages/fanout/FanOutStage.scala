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

package swave.core.impl.stages.fanout

import scala.annotation.compileTimeOnly
import scala.collection.mutable.ListBuffer
import swave.core.PipeElem
import swave.core.impl.{ Inport, RunContext }
import swave.core.impl.stages.Stage
import swave.core.impl.stages.Stage.OutportStates

// format: OFF
private[core] abstract class FanOutStage extends Stage { this: PipeElem.FanOut =>

  protected var _inputPipeElem: PipeElem.Basic = PipeElem.Unconnected
  protected var _outputElems: OutportStates = _

  def inputElem = _inputPipeElem
  def outputElems =  {
    val buf = new ListBuffer[PipeElem.Basic]
    for (o <- _outputElems) {
      buf += o.out.asInstanceOf[PipeElem.Basic]
      ()
    }
    buf.result()
  }

  @compileTimeOnly("Unresolved `connectFanOutAndSealWith` call")
  protected final def connectFanOutAndSealWith(f: (RunContext, Inport, OutportStates) ⇒ State): Unit = ()
}