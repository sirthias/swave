/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.source

import org.reactivestreams.{ Subscriber, Subscription }
import swave.core.PipeElem
import swave.core.impl.Outport
import swave.core.impl.rs.ForwardToRunnerSubscriber
import swave.core.macros.StageImpl
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class SubscriberSourceStage extends SourceStage with PipeElem.Source.Subscriber {

  def pipeElemType: String = "Stream.withSubscriber"
  def pipeElemParams: List[Any] = Nil

  val subscriber: Subscriber[AnyRef] = new ForwardToRunnerSubscriber(this)

  connectOutAndSealWith { (ctx, out) ⇒
    ctx.registerForRunnerAssignment(this)
    ctx.registerForXStart(this)
    awaitingXStart(out)
  }

  def awaitingXStart(out: Outport) = state(
    xStart = () => awaitingSubscription(out, 0))

  /**
   * @param out       the active downstream
   * @param requested the number of elements already requested by the downstream,
   *                  -1: downstream already cancelled
   */
  def awaitingSubscription(out: Outport, requested: Long): State = state(
    request = (n, _) => if (requested >= 0) awaitingSubscription(out, requested ⊹ n) else stay(),
    cancel = _ => awaitingSubscription(out, -1),

    xEvent = { case s: Subscription =>
      if (requested >= 0) {
        if (requested > 0) s.request(requested)
        running(out, s)
      } else {
        s.cancel()
        stop()
      }
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
