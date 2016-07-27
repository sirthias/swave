/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.spout

import org.reactivestreams.{ Subscriber, Subscription, Publisher }
import swave.core.PipeElem
import swave.core.impl.Outport
import swave.core.impl.rs.RSCompliance
import swave.core.macros.StageImpl
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class PublisherSpoutStage(publisher: Publisher[AnyRef])
  extends SpoutStage with PipeElem.Spout.Publisher { stage =>

  def pipeElemType: String = "Spout.fromPublisher"
  def pipeElemParams: List[Any] = publisher :: Nil

  connectOutAndSealWith { (ctx, out) ⇒
    ctx.registerForRunnerAssignment(this)
    ctx.registerForXStart(this)
    awaitingXStart(out)
  }

  def awaitingXStart(out: Outport) = state(
    xStart = () => {
      publisher.subscribe {
        new Subscriber[AnyRef] {
          def onSubscribe(s: Subscription) = {
            RSCompliance.verifyNonNull(s, "Subscription", "2.13")
            runner.enqueueXEvent(stage, s)
          }
          def onNext(elem: AnyRef) = {
            RSCompliance.verifyNonNull(elem, "Element", "2.13")
            runner.enqueueOnNext(stage, elem)(stage)
          }
          def onComplete() = runner.enqueueOnComplete(stage)(stage)
          def onError(e: Throwable) = {
            RSCompliance.verifyNonNull(e, "Throwable", "2.13")
            stage.runner.enqueueOnError(stage, e)(stage)
          }
        }
      }
      awaitingSubscription(out, 0L)
    })

  def awaitingSubscription(out: Outport, requested: Long): State = state(
    request = (n, _) ⇒ awaitingSubscription(out, requested ⊹ n),
    cancel = _ => awaitingSubscriptionDownstreamCancelled(),

    xEvent = {
      case s: Subscription =>
        if (requested > 0) s.request(requested)
        running(out, s)
    })

  def awaitingSubscriptionDownstreamCancelled(): State = state(
    request = (_, _) ⇒ stay(),
    cancel = _ => stay(),

    xEvent = {
      case s: Subscription =>
        s.cancel()
        stop()
    })

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
