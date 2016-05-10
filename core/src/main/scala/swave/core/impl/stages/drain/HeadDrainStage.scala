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

package swave.core
package impl
package stages
package drain

import scala.concurrent.Promise

// format: OFF
private[core] final class HeadDrainStage(headPromise: Promise[AnyRef]) extends DrainStage with PipeElem.Drain.Head {

  def pipeElemType: String = "Drain.head"
  def pipeElemParams: List[Any] = headPromise :: Nil

  connectInAndStartWith { (ctx, in) ⇒
    ctx.registerForXStart(this)
    awaitingXStart(in)
  }

  def awaitingXStart(in: Inport) =
    state(name = "awaitingXStart",

      xStart = () => {
        in.request(1)
        receiveOne(in)
      })

  def receiveOne(in: Inport) =
    state(name = "receiveOne",

      onNext = (elem, _) ⇒ {
        headPromise.success(elem)
        stopCancel(in)
      },

      onComplete = _ ⇒ {
        headPromise.failure(new NoSuchElementException())
        stop()
      },

      onError = (e, _) ⇒ {
        headPromise.failure(e)
        stop()
      })
}

