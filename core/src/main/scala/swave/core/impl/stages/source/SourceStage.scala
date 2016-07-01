/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.source

import scala.annotation.compileTimeOnly
import swave.core.PipeElem
import swave.core.impl.{ Outport, RunContext }
import swave.core.impl.stages.Stage

// format: OFF
private[swave] abstract class SourceStage extends Stage { this: PipeElem.Source ⇒

  protected final var _outputPipeElem: PipeElem.Basic = PipeElem.Unconnected

  final def outputElem = _outputPipeElem

  @compileTimeOnly("Unresolved `connectOutAndSealWith` call")
  protected final def connectOutAndSealWith(f: (RunContext, Outport) ⇒ State): Unit = ()
}
