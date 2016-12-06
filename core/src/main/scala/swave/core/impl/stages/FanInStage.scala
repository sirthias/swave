/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import scala.annotation.compileTimeOnly
import swave.core.Stage
import swave.core.impl.util.InportList
import swave.core.impl.{Outport, RunSupport}

// format: OFF
private[core] abstract class FanInStage(subs: InportList) extends StageImpl {

  override def kind: Stage.Kind.FanIn

  protected final var _outputStages: List[Stage] = Nil

  final val inputStages: List[Stage] = subs.toStageList
  final def outputStages: List[Stage] = _outputStages

  @compileTimeOnly("Unresolved `connectFanInAndSealWith` call")
  protected final def connectFanInAndSealWith(f: (RunSupport.SealingContext, Outport) ⇒ State): Unit = ()
}
