/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import swave.core.PipeElem
import swave.core.impl.{Inport, Outport}
import swave.core.macros.StageImpl

import scala.util.control.NonFatal

// format: OFF
@StageImpl
private[core] final class TakeWhileStage(predicate: Any ⇒ Boolean) extends InOutStage with PipeElem.InOut.TakeWhile {

  def pipeElemType: String = "takeWhile"
  def pipeElemParams: List[Any] = predicate :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      var funError: Throwable = null
      val p = try predicate(elem) catch { case NonFatal(e) => { funError = e; false } }
      if (funError eq null) {
        if (p) {
          out.onNext(elem)
          stay()
        } else {
          in.cancel()
          stopComplete(out)
        }
      } else {
        in.cancel()
        stopError(funError, out)
      }
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
