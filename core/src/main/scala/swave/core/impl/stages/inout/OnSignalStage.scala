/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.macros.StageImplementation
import swave.core.impl.{Inport, Outport}
import swave.core.impl.stages.InOutStage
import swave.core.{Stage, StreamEvent}

// format: OFF
@StageImplementation
private[core] final class OnSignalStage(callback: StreamEvent[Any] ⇒ Unit) extends InOutStage {

  def kind = Stage.Kind.InOut.OnSignal(callback)

  connectInOutAndSealWith { (in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport) = state(
    intercept = false,

    request = (n, _) ⇒ {
      val callbackError = try { callback(StreamEvent.Request(n)); null } catch { case NonFatal(e) => e }
      if (callbackError eq null) {
        in.request(n.toLong)
        stay()
      } else {
        in.cancel()
        stopError(callbackError, out)
      }
    },

    cancel = _ ⇒ {
      try callback(StreamEvent.Cancel)
      catch {
        case NonFatal(e) => () // no point in forwarding the error since our downstream is already cancelled
      }
      stopCancel(in)
    },

    onNext = (elem, _) ⇒ {
      val callbackError = try { callback(StreamEvent.OnNext(elem)); null } catch { case NonFatal(e) => e }
      if (callbackError eq null) {
        out.onNext(elem)
        stay()
      } else {
        in.cancel()
        stopError(callbackError, out)
      }
    },

    onComplete = _ ⇒ {
      val callbackError =
        try {
          callback(StreamEvent.OnComplete)
          null
        } catch { case NonFatal(e) => e }
      if (callbackError eq null) stopComplete(out)
      else stopError(callbackError, out)
    },

    onError = (e, _) ⇒ {
      // if the callback throws an exception we prioritize it over the stream error so as to not drop it silently
      val error =
        try {
          callback(StreamEvent.OnError(e))
          e
        } catch { case NonFatal(x) => x }
      stopError(error, out)
    })
}
