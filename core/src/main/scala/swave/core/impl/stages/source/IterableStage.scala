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

import scala.annotation.tailrec
import swave.core.PipeElem
import swave.core.impl.Outport

// format: OFF
private[core] final class IterableStage(iterable: Iterable[AnyRef]) extends SourceStage with PipeElem.Source.Iterable {

  def pipeElemType: String = "Stream.fromIterable"
  def pipeElemParams: List[Any] = iterable :: Nil

  connectOutAndStartWith { (ctx, out) ⇒
    if (iterable.nonEmpty) running(out, iterable.iterator)
    else stopComplete(out)
  }

  def running(out: Outport, iterator: Iterator[AnyRef]) =
    state(name = "running",

      request = (n, _) ⇒ {
        @tailrec def rec(n: Int): State = {
          out.onNext(iterator.next())
          if (iterator.hasNext) {
            if (n > 1) rec(n - 1)
            else stay()
          } else stopComplete(out)
        }
        rec(n)
      },

      cancel = stopF)
}

