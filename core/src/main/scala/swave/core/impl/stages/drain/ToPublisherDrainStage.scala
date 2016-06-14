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

import org.reactivestreams.{ Publisher, Subscriber }
import swave.core.{ UnsupportedSecondSubscriptionException, PipeElem }
import swave.core.impl.Inport
import swave.core.impl.rs.{ ForwardToRunnerPublisher, ForwardToRunnerSubscription, SyncSubscription }
import swave.core.impl.stages.StreamTermination
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class ToPublisherDrainStage extends DrainStage with PipeElem.Drain.Publisher {

  def pipeElemType: String = "Drain.toPublisher"
  def pipeElemParams: List[Any] = Nil

  val publisher: Publisher[AnyRef] = new ForwardToRunnerPublisher(this)

  connectInAndSealWith { (ctx, in) ⇒
    ctx.registerForRunnerAssignment(this)
    ctx.registerForXStart(this)
    awaitingXStart(in)
  }

  def awaitingXStart(in: Inport) = state(
    xStart = () => awaitingSubscriber(in, StreamTermination.None))

  def awaitingSubscriber(in: Inport, termination: StreamTermination): State = state(
    onComplete = _ => awaitingSubscriber(in, StreamTermination.Completed),
    onError = (e, _) => awaitingSubscriber(in, StreamTermination.Error(e)),

    xEvent = { case sub: Subscriber[_] =>
      termination match {
        case StreamTermination.None =>
          sub.onSubscribe(new ForwardToRunnerSubscription(this))
          running(in, sub.asInstanceOf[Subscriber[AnyRef]])

        case StreamTermination.Completed =>
          val s = new SyncSubscription
          sub.onSubscribe(s)
          if (!s.cancelled) sub.onComplete()
          stop()

        case StreamTermination.Error(e) =>
          val s = new SyncSubscription
          sub.onSubscribe(s)
          if (!s.cancelled) sub.onError(e)
          stop()
      }
    })

  def running(in: Inport, subscriber: Subscriber[AnyRef]) = state(
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

    xEvent = {
      case sub: Subscriber[_] =>
        val s = new SyncSubscription
        sub.onSubscribe(s)
        if (!s.cancelled) sub.onError(new UnsupportedSecondSubscriptionException)
        stay()

      case ForwardToRunnerSubscription.IllegalRequest(n) =>
        subscriber.onError(new IllegalArgumentException(
          "The number of elements requested must be > 0 (see reactive-streams spec, rule 3.9)"))
        stopCancel(in)
    })
}