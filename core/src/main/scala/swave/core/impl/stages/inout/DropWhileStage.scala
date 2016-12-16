/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.Stage
import swave.core.impl.stages.InOutStage
import swave.core.impl.{Inport, Outport}
import swave.core.macros.StageImplementation

// format: OFF
@StageImplementation
private[core] final class DropWhileStage(predicate: Any ⇒ Boolean) extends InOutStage {

  def kind = Stage.Kind.InOut.DropWhile(predicate)

  connectInOutAndSealWith { (in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport) = {

    /**
      * Dropping elements until predicate returns true.
      */
    def dropping(): State = state(
      request = requestF(in),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        var funError: Throwable = null
        val drop = try predicate(elem) catch { case NonFatal(e) => { funError = e; false } }
        if (funError eq null) {
          if (drop) {
            in.request(1)
            stay()
          } else {
            out.onNext(elem)
            draining()
          }
        } else {
          in.cancel()
          stopError(funError, out)
        }
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
