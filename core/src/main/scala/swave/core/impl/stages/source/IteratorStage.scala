/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.source

import scala.util.control.NonFatal
import scala.annotation.tailrec
import swave.core.macros.StageImpl
import swave.core.PipeElem
import swave.core.impl.Outport

// format: OFF
@StageImpl
private[core] final class IteratorStage(iterator: Iterator[AnyRef]) extends SourceStage with PipeElem.Source.Iterator {

  def pipeElemType: String = "Stream.fromIterator"
  def pipeElemParams: List[Any] = iterator :: Nil

  connectOutAndSealWith { (ctx, out) ⇒
    if (!iterator.hasNext) {
      ctx.registerForXStart(this)
      awaitingXStart(out)
    } else running(out)
  }

  def awaitingXStart(out: Outport) = state(
    xStart = () => stopComplete(out))

  def running(out: Outport) = state(
    request = (n, _) ⇒ {
      @tailrec def rec(nn: Int): State = {
        var iterError: Throwable = null
        val next = try iterator.next() catch { case NonFatal(e) => { iterError = e; null } }
        if (iterError eq null) {
          out.onNext(next)
          if (iterator.hasNext) {
            if (nn > 1) rec(nn - 1)
            else stay()
          } else stopComplete(out)
        } else stopError(iterError, out)
      }
      rec(n)
    },

    cancel = stopF)
}
