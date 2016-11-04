/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.fanout

import scala.annotation.compileTimeOnly
import scala.collection.mutable.ListBuffer
import swave.core.PipeElem
import swave.core.impl.{Inport, Outport, RunContext}
import swave.core.impl.stages.Stage
import swave.core.impl.util.AbstractOutportList

// format: OFF
private[core] abstract class FanOutStage extends Stage { this: PipeElem.FanOut =>
  import FanOutStage._

  type OutportCtx >: Null <: OutportContext[OutportCtx]

  protected final var _inputPipeElem: PipeElem = PipeElem.Unconnected
  protected final var _outputElems: OutportCtx = _

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
  protected final def connectFanOutAndSealWith(f: (RunContext, Inport, OutportCtx) ⇒ State): Unit = ()

  protected def createOutportCtx(out: Outport, tail: OutportCtx): OutportCtx
}

private[fanout] object FanOutStage {

  private[fanout] abstract class OutportContext[L >: Null <: OutportContext[L]](out: Outport, tail: L)
    extends AbstractOutportList[L](out, tail) {
    var remaining: Long = _ // requested by this `out` but not yet delivered, i.e. unfulfilled demand
  }

  private[fanout] final class SimpleOutportContext(out: Outport, tail: SimpleOutportContext)
    extends OutportContext[SimpleOutportContext](out, tail)
}