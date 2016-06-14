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

package swave.core.impl.stages.drain

import org.reactivestreams.Subscriber
import swave.core.PipeElem
import swave.core.impl.Inport
import swave.core.impl.rs.ForwardToRunnerSubscription
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class FromSubscriberStage(subscriber: Subscriber[AnyRef])
  extends DrainStage with PipeElem.Drain.FromSubscriber {

  def pipeElemType: String = "Drain.fromSubscriber"
  def pipeElemParams: List[Any] = subscriber :: Nil

  connectInAndSealWith { (ctx, in) ⇒
    ctx.registerForRunnerAssignment(this)
    ctx.registerForXStart(this)
    awaitingXStart(in)
  }

  def awaitingXStart(in: Inport): State = state(
    xStart = () => {
      subscriber.onSubscribe(new ForwardToRunnerSubscription(this))
      running(in)
    })

  def running(in: Inport): State = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) => {
      subscriber.onNext(elem)
      stay()
    },

    onComplete = _ => {
      subscriber.onComplete()
      stop()
    },

    onError = (e, _) => {
      subscriber.onError(e)
      stop()
    },

    xEvent = { case ForwardToRunnerSubscription.IllegalRequest(n) =>
        subscriber.onError(new IllegalArgumentException(
          "The number of elements requested must be > 0 (see reactive-streams spec, rule 3.9)"))
        stopCancel(in)
    })
}

