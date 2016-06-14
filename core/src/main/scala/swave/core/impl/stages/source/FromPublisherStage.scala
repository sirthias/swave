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

import org.reactivestreams.{ Subscription, Publisher }
import swave.core.PipeElem
import swave.core.impl.Outport
import swave.core.impl.rs.ForwardToRunnerSubscriber
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class FromPublisherStage(publisher: Publisher[AnyRef])
  extends SourceStage with PipeElem.Source.FromPublisher {

  def pipeElemType: String = "Stream.fromPublisher"
  def pipeElemParams: List[Any] = publisher :: Nil

  connectOutAndSealWith { (ctx, out) ⇒
    ctx.registerForRunnerAssignment(this)
    ctx.registerForXStart(this)
    awaitingXStart(out)
  }

  def awaitingXStart(out: Outport) = state(
    xStart = () => {
      publisher.subscribe(new ForwardToRunnerSubscriber(this))
      awaitingSubscription(out)
    })

  def awaitingSubscription(out: Outport) = state(
    xEvent = { case s: Subscription => running(out, s) })

  def running(out: Outport, subscription: Subscription) = state(
    intercept = false,

    request = (n, _) ⇒ {
      subscription.request(n.toLong)
      stay()
    },

    cancel = _ => {
      subscription.cancel()
      stop()
    },

    onNext = onNextF(out),
    onComplete = stopCompleteF(out),
    onError = stopErrorF(out),

    xEvent = { case s: Subscription =>
      s.cancel()
      stay()
    })
}

