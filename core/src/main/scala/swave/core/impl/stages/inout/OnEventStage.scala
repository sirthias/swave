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

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.impl.{ Outport, Inport }
import swave.core.macros.StageImpl
import swave.core.{ PipeElem, StreamEvent }

// format: OFF
@StageImpl
private[core] final class OnEventStage(callback: StreamEvent[Any] ⇒ Unit) extends InOutStage
  with PipeElem.InOut.OnEvent {

  def pipeElemType: String = "onEvent"
  def pipeElemParams: List[Any] = callback :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport) = state(
    intercept = false,

    request = (n, _) ⇒ {
      try {
        callback(StreamEvent.Request(n))
        in.request(n.toLong)
        stay()
      } catch { case NonFatal(e) => { in.cancel(); stopError(e, out) } }
    },

    cancel = _ ⇒ {
      try {
        callback(StreamEvent.Cancel)
        stopCancel(in)
      } catch { case NonFatal(e) => { in.cancel(); stopError(e, out) } }
    },

    onNext = (elem, _) ⇒ {
      try {
        callback(StreamEvent.OnNext(elem))
        out.onNext(elem)
        stay()
      } catch { case NonFatal(e) => { in.cancel(); stopError(e, out) } }
    },

    onComplete = _ ⇒ {
      try {
        callback(StreamEvent.OnComplete)
        stopComplete(out)
      } catch { case NonFatal(e) => { in.cancel(); stopError(e, out) } }
    },

    onError = (e, _) ⇒ {
      try {
        callback(StreamEvent.OnError(e))
        stopError(e, out)
      } catch { case NonFatal(e) => { in.cancel(); stopError(e, out) } }
    })
}

