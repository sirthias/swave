/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.fanin

import scala.annotation.compileTimeOnly
import scala.collection.mutable.ListBuffer
import swave.core.PipeElem
import swave.core.impl.stages.Stage
import swave.core.impl.{ Outport, RunContext, InportList }

// format: OFF
private[core] abstract class FanInStage extends Stage { this: PipeElem.FanIn =>

  protected final var _outputPipeElem: PipeElem = PipeElem.Unconnected
  protected final var _inputElems = InportList.empty

  final def outputElem = _outputPipeElem
  final def inputElems = {
    val buf = new ListBuffer[PipeElem]
    for (i <- _inputElems) {
      buf += i.in.asInstanceOf[PipeElem]
      ()
    }
    buf.result()
  }

  @compileTimeOnly("Unresolved `connectFanInAndSealWith` call")
  protected final def connectFanInAndSealWith(subs: InportList)(f: (RunContext, Outport) ⇒ State): Unit = ()
}
