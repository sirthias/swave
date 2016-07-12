/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.source

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import org.reactivestreams.{ Subscriber, Subscription }
import swave.core.PipeElem
import swave.core.impl.{ StreamRunner, Outport }
import swave.core.impl.rs.RSCompliance
import swave.core.macros.StageImpl
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class SubscriberSourceStage extends SourceStage with PipeElem.Source.Subscriber { stage =>

  def pipeElemType: String = "Stream.withSubscriber"
  def pipeElemParams: List[Any] = Nil

  // holds exactly one of these values:
  // - `null`, when the stage is unstarted and no subscription has been received yet
  // - a `Subscription` instance, when a subscription request has been received before the stage was started
  // - the stage instance, when a subscription and completion has been received before the stage was started
  // - a `Throwable` instance, when a subscription and error has been received before the stage was started
  // - `stage.runner`, when the stage was started
  private val refSub =
    new AtomicReference[AnyRef] with Subscriber[AnyRef] {
      @tailrec def onSubscribe(s: Subscription): Unit = {
        RSCompliance.verifyNonNull(s, "Subscription", "2.13")
        get match {
          case null => if (!compareAndSet(null, s)) onSubscribe(s)
          case x: StreamRunner => x.enqueueXEvent(stage, s)
          case _ => s.cancel()
        }
      }
      def onNext(elem: AnyRef) = {
        RSCompliance.verifyNonNull(elem, "Element", "2.13")
        get match {
          case x: StreamRunner => x.enqueueOnNext(stage, elem)(stage)
          case _ => // drop
        }
      }
      @tailrec def onComplete() =
        get match {
          case _: Subscription => if (!compareAndSet(null, stage)) onComplete()
          case x: StreamRunner => x.enqueueOnComplete(stage)(stage)
          case _ => // drop
        }
      @tailrec def onError(e: Throwable) = {
        RSCompliance.verifyNonNull(e, "Throwable", "2.13")
        get match {
          case _: Subscription => if (!compareAndSet(null, e)) onError(e)
          case x: StreamRunner => x.enqueueOnError(stage, e)(stage)
          case _ => // drop
        }
      }
    }

  def subscriber: Subscriber[AnyRef] = refSub

  connectOutAndSealWith { (ctx, out) ⇒
    ctx.registerForRunnerAssignment(this)
    ctx.registerForXStart(this)
    awaitingXStart(out)
  }

  def awaitingXStart(out: Outport) = state(
    xStart = () => {
      @tailrec def rec(): State =
        refSub.get match {
          case null if refSub.compareAndSet(null, runner) => awaitingSubscription(out, 0)
          case null => rec()
          case sub: Subscription => { refSub.set(runner); running(out, sub) }
          case `stage` => { refSub.set(runner); stopComplete(out) }
          case e: Throwable => { refSub.set(runner); stopError(e, out) }
        }
      rec()
    })

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
