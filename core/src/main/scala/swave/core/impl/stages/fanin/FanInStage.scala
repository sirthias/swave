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
import swave.core.impl.{ InportList, Outport, StartContext }

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

  protected final def connectFanInAndStartWith(subs: InportList)(f: (StartContext, Outport) ⇒ State): Unit = {
    def connecting(out: Outport, pendingSubs: InportList): State =
      fullState(name = "connectFanInAndStartWith:connecting",

        onSubscribe = sub ⇒ {
          val newPending = pendingSubs.remove_!(sub)
          if ((out ne null) && newPending.isEmpty) ready(out)
          else connecting(out, newPending)
        },

        subscribe = outPort ⇒ {
          if (out eq null) {
            _outputPipeElem = outPort.pipeElem
            outPort.onSubscribe()
            if (pendingSubs.isEmpty) ready(outPort)
            else connecting(outPort, pendingSubs)
          } else doubleSubscribe(outPort)
        })

    def ready(out: Outport) =
      fullState(name = "connectFanInAndStartWith:ready",
        subscribe = doubleSubscribe,
        onSubscribe = doubleOnSubscribe,

        start = ctx ⇒ {
          configureFrom(ctx.env)
          out.start(ctx)
          subs.foreach(_.in.start(ctx)) // TODO: avoid function allocation
          f(ctx, out)
        })

    _inputElems = subs
    initialState(connecting(out = null, subs))
    subs.foreach(_.in.subscribe()) // TODO: avoid function allocation
  }
}