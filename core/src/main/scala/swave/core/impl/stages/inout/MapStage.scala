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
private[core] final class MapStage(f: Any ⇒ AnyRef) extends InOutStage {

  def kind = Stage.Kind.InOut.Map(f)

  connectInOutAndSealWith { (in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport): State = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      var funError: Throwable = null
      val mapped = try f(elem) catch { case NonFatal(e) => { funError = e; null } }
      if (funError eq null) {
        out.onNext(mapped)
        stay()
      } else {
        in.cancel()
        stopError(funError, out)
      }
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
