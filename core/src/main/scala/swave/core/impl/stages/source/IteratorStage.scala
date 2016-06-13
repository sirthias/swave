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
      try {
        @tailrec def rec(nn: Int): State = {
          out.onNext(iterator.next())
          if (iterator.hasNext) {
            if (nn > 1) rec(nn - 1)
            else stay()
          } else stopComplete(out)
        }
        rec(n)
      } catch { case NonFatal(e) => stopError(e, out) }
    },

    cancel = stopF)
}

