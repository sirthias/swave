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

package swave.core.impl.stages.fanout

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import swave.core.PipeElem
import swave.core.impl.{ Inport, Outport, StartContext }
import swave.core.impl.stages.PipeStage
import swave.core.impl.stages.Stage.OutportStates

// format: OFF
private[fanout] abstract class FanOutStage extends PipeStage { this: PipeElem.FanOut =>

  private[this] var _inputPipeElem: PipeElem.Basic = PipeElem.Unconnected
  private[this] var _outputElems: OutportStates = _

  def inputElem = _inputPipeElem
  def outputElems =  {
    val buf = new ListBuffer[PipeElem.Basic]
    for (o <- _outputElems) {
      buf += o.out.asInstanceOf[PipeElem.Basic]
      ()
    }
    buf.result()
  }

  protected final def connectFanOutAndStartWith(f: (StartContext, Inport, OutportStates) ⇒ State): Unit = {

    def connecting(in: Inport, outs: OutportStates): State = {
      fullState(name = "connectFanOutAndStartWith:connecting",

        onSubscribe = from ⇒ {
          if (in eq null) {
            _inputPipeElem = from.pipeElem
            connecting(from, outs)
          } else doubleOnSubscribe(from)
        },

        subscribe = outPort ⇒ {
          @tailrec def rec(outPort: Outport, current: OutportStates): State =
            if (current.nonEmpty) {
              if (current.out ne outPort) rec(outPort, current.tail)
              else doubleSubscribe(outPort)
            } else {
              val newOuts = new OutportStates(outPort, outs, 0)
              _outputElems = newOuts
              outPort.onSubscribe()
              connecting(in, newOuts)
            }
          rec(outPort, outs)
        },

        start = ctx ⇒ {
          if (in ne null) {
            if (outs.nonEmpty) {
              configureFrom(ctx)
              in.start(ctx)
              @tailrec def rec(current: OutportStates): Unit =
                if (current ne null) { current.out.start(ctx); rec(current.tail) }
              rec(outs)
              f(ctx, in, outs)
            } else illegalState(s"Cannot start without downstream in $this")
          } else illegalState(s"Cannot start with unconnected upstream in $this")
        })
    }

    initialState(connecting(in = null, outs = null))
  }
}