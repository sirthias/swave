/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import scala.annotation.compileTimeOnly
import swave.core.Stage
import swave.core.impl.{Outport, RunSupport}

// format: OFF
private[swave] abstract class SpoutStage extends StageImpl {

  def kind: Stage.Kind.Spout

  protected var _outputStages: List[Stage] = Nil

  final def inputStages: List[Stage] = Nil
  override def outputStages: List[Stage] = _outputStages

  @compileTimeOnly("Unresolved `connectOutAndSealWith` call")
  protected final def connectOutAndSealWith(f: (RunSupport.SealingContext, Outport) ⇒ State): Unit = ()
}
