/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

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
