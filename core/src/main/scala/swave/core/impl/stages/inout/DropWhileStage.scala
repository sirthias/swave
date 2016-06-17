/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.PipeElem
import swave.core.impl.{ Inport, Outport }
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class DropWhileStage(predicate: Any ⇒ Boolean) extends InOutStage with PipeElem.InOut.DropWhile {

  def pipeElemType: String = "dropWhile"
  def pipeElemParams: List[Any] = predicate :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport) = {

    /**
     * Dropping elements until predicate returns true.
     */
    def dropping(): State = state(
      request = requestF(in),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        try {
          if (predicate(elem)) {
            in.request(1)
            stay()
          } else {
            out.onNext(elem)
            draining()
          }
        } catch { case NonFatal(e) => { in.cancel(); stopError(e, out) } }
      },

      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    /**
     * Simply forwarding elements from upstream to downstream.
     */
    def draining() = state(
      intercept = false,

      request = requestF(in),
      cancel = stopCancelF(in),
      onNext = onNextF(out),
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    dropping()
  }
}
