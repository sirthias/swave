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

