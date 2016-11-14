/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.spout

import scala.annotation.tailrec
import swave.core.macros.StageImplementation
import swave.core.Stage
import swave.core.impl.Outport

// format: OFF
@StageImplementation
private[core] final class RepeatSpoutStage(element: AnyRef) extends SpoutStage {

  def kind = Stage.Kind.Spout.Repeat(element)

  connectOutAndSealWith { (ctx, out) ⇒ running(out) }

  def running(out: Outport): State = state(
    request = (n, _) ⇒ {
      @tailrec def rec(n: Int): State =
        if (n > 0) {
          out.onNext(element)
          rec(n - 1)
        } else stay()
      rec(n)
    },

    cancel = stopF)
}
