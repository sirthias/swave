/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import scala.annotation.compileTimeOnly
import swave.core.Stage
import swave.core.impl.util.AbstractOutportList
import swave.core.impl.{Inport, Outport, RunContext}

// format: OFF
private[core] abstract class FanOutStage extends StageImpl {
  import FanOutStage._

  type OutportCtx >: Null <: OutportContext[OutportCtx]

  override def kind: Stage.Kind.FanOut

  protected final var _inputStages: List[Stage] = Nil
  protected final var _outputStages: List[Stage] = Nil

  final def inputStages: List[Stage] = _inputStages
  final def outputStages: List[Stage] = _outputStages

  @compileTimeOnly("Unresolved `connectFanOutAndSealWith` call")
  protected final def connectFanOutAndSealWith(f: (RunContext, Inport, OutportCtx) ⇒ State): Unit = ()

  protected def createOutportCtx(out: Outport, tail: OutportCtx): OutportCtx
}

private[stages] object FanOutStage {

  private[stages] abstract class OutportContext[L >: Null <: OutportContext[L]](out: Outport, tail: L)
    extends AbstractOutportList[L](out, tail) {
    var remaining: Long = _ // requested by this `out` but not yet delivered, i.e. unfulfilled demand
  }

  private[stages] final class SimpleOutportContext(out: Outport, tail: SimpleOutportContext)
    extends OutportContext[SimpleOutportContext](out, tail)
}