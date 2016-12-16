/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.spout

import scala.annotation.tailrec
import scala.util.control.NonFatal
import swave.core.macros.StageImplementation
import swave.core.Stage
import swave.core.impl.Outport
import swave.core.impl.stages.SpoutStage

// format: OFF
@StageImplementation
private[core] final class IteratorSpoutStage(iterator: Iterator[AnyRef]) extends SpoutStage {

  def kind = Stage.Kind.Spout.FromIterator(iterator)

  connectOutAndSealWith { out ⇒
    if (!iterator.hasNext) {
      region.impl.registerForXStart(this)
      awaitingXStart(out)
    } else running(out)
  }

  def awaitingXStart(out: Outport) = state(
    xStart = () => stopComplete(out))

  def running(out: Outport) = state(
    request = (n, _) ⇒ {
      @tailrec def rec(n: Int): State = {
        var iterError: Throwable = null
        val next = try iterator.next() catch { case NonFatal(e) => { iterError = e; null } }
        if (iterError eq null) {
          out.onNext(next)
          if (iterator.hasNext) {
            if (n > 1) rec(n - 1)
            else stay()
          } else stopComplete(out)
        } else stopError(iterError, out)
      }
      rec(n)
    },

    cancel = stopF)
}
