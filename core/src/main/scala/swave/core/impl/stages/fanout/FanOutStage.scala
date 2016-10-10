/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.fanout

import scala.annotation.compileTimeOnly
import scala.collection.mutable.ListBuffer
import swave.core.PipeElem
import swave.core.impl.{Inport, RunContext}
import swave.core.impl.stages.Stage
import swave.core.impl.stages.Stage.OutportStates

// format: OFF
private[core] abstract class FanOutStage extends Stage { this: PipeElem.FanOut =>

  protected final var _inputPipeElem: PipeElem = PipeElem.Unconnected
  protected final var _outputElems: OutportStates = _

  final def inputElem = _inputPipeElem
  final def outputElems =  {
    val buf = new ListBuffer[PipeElem]
    for (o <- _outputElems) {
      buf += o.out.asInstanceOf[PipeElem]
      ()
    }
    buf.result()
  }

  @compileTimeOnly("Unresolved `connectFanOutAndSealWith` call")
  protected final def connectFanOutAndSealWith(f: (RunContext, Inport, OutportStates) ⇒ State): Unit = ()
}
