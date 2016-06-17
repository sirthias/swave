/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

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
