/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.source

import org.reactivestreams.{ Subscriber, Subscription, Publisher }
import swave.core.PipeElem
import swave.core.impl.Outport
import swave.core.impl.rs.RSCompliance
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class FromPublisherStage(publisher: Publisher[AnyRef])
  extends SourceStage with PipeElem.Source.FromPublisher { stage =>

  def pipeElemType: String = "Stream.fromPublisher"
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
