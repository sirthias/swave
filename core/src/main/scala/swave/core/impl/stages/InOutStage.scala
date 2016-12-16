/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import scala.annotation.compileTimeOnly
import swave.core.Stage
import swave.core.impl.{Inport, Outport}

// format: OFF
private[core] abstract class InOutStage extends StageImpl {

  override def kind: Stage.Kind

  protected final var _inputStages: List[Stage] = Nil
  protected final var _outputStages: List[Stage] = Nil

  final def inputStages: List[Stage] = _inputStages
  final def outputStages: List[Stage] = _outputStages

  @compileTimeOnly("Unresolved `connectInOutAndSealWith` call")
  protected final def connectInOutAndSealWith(f: (Inport, Outport) ⇒ State): Unit = ()

  @compileTimeOnly("Unresolved `connectInOutAndSealWith_NoAutoPropagation` call")
  protected final def connectInOutAndSealWith_NoAutoPropagation(f: (Inport, Outport) ⇒ State): Unit = ()
}
