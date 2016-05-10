/*
 * Copyright © 2016 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swave.core.impl.stages.fanin

import scala.collection.mutable.ListBuffer
import swave.core.PipeElem
import swave.core.impl.stages.PipeStage
import swave.core.impl.{ InportList, Outport, RunContext }

// format: OFF
private[fanin] abstract class FanInStage extends PipeStage  { this: PipeElem.FanIn =>

  private[this] var _outputPipeElem: PipeElem.Basic = PipeElem.Unconnected
  private[this] var _inputElems = InportList.empty

  def outputElem = _outputPipeElem
  def inputElems = {
    val buf = new ListBuffer[PipeElem.Basic]
    for (i <- _inputElems) {
      buf += i.in.asInstanceOf[PipeElem.Basic]
      ()
    }
    buf.result()
  }

  protected final def connectFanInAndStartWith(subs: InportList)(f: (RunContext, Outport) ⇒ State): Unit = {
    def connecting(out: Outport, pendingSubs: InportList): State =
      fullState(name = "connectFanInAndStartWith:connecting",
        interceptWhileHandling = false,

        onSubscribe = sub ⇒ {
          val newPending = pendingSubs.remove_!(sub)
          if ((out ne null) && newPending.isEmpty) ready(out)
          else connecting(out, newPending)
        },

        subscribe = from ⇒ {
          if (out eq null) {
            _outputPipeElem = from.pipeElem
            from.onSubscribe()
            if (pendingSubs.isEmpty) ready(from)
            else connecting(from, pendingSubs)
          } else illegalState(s"Double subscribe($from) in $this")
        })

    def ready(out: Outport) =
      fullState(name = "connectFanInAndStartWith:ready",

        xSeal = ctx ⇒ {
          configureFrom(ctx.env)
          out.xSeal(ctx)
          subs.foreach(_.in.xSeal(ctx)) // TODO: avoid function allocation
          f(ctx, out)
        })

    _inputElems = subs
    initialState(connecting(out = null, subs))
    subs.foreach(_.in.subscribe()) // TODO: avoid function allocation
  }
}