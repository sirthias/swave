/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import scala.annotation.compileTimeOnly
import swave.core.Stage
import swave.core.impl.{Inport, RunSupport}

// format: OFF
private[swave] abstract class DrainStage extends StageImpl {

  def kind: Stage.Kind.Drain

  protected var _inputStages: List[Stage] = Nil

  override def inputStages: List[Stage] = _inputStages
  final def outputStages: List[Stage] = Nil

  @compileTimeOnly("Unresolved `connectInAndSealWith` call")
  protected final def connectInAndSealWith(f: (RunSupport.SealingContext, Inport) ⇒ State): Unit = ()
}