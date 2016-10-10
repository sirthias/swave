/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.annotation.compileTimeOnly
import swave.core.impl.{Inport, Outport, RunContext}
import swave.core.PipeElem
import swave.core.impl.stages.Stage

// format: OFF
private[core] abstract class InOutStage extends Stage { this: PipeElem.InOut =>

  protected final var _inputPipeElem: PipeElem = PipeElem.Unconnected
  protected final var _outputPipeElem: PipeElem = PipeElem.Unconnected

  final def inputElem = _inputPipeElem
  final def outputElem =  _outputPipeElem

  @compileTimeOnly("Unresolved `connectInOutAndSealWith` call")
  protected final def connectInOutAndSealWith(f: (RunContext, Inport, Outport) ⇒ State): Unit = ()
}
