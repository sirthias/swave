/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.rs

import org.reactivestreams._
import swave.core.impl.stages.StageImpl

private[impl] class ForwardToRunnerSubscription(stage: StageImpl) extends Subscription {
  def request(n: Long) =
    if (n > 0) stage.region.enqueueRequest(stage, n, stage)
    else stage.region.enqueueXEvent(stage, ForwardToRunnerSubscription.IllegalRequest(n))
  def cancel() = stage.region.enqueueCancel(stage, stage)
}

private[impl] object ForwardToRunnerSubscription {
  final case class IllegalRequest(n: Long)
}

private[core] class SubPubProcessor[A, B](sub: Subscriber[A], pub: Publisher[B]) extends Processor[A, B] {
  override def subscribe(s: Subscriber[_ >: B]): Unit = pub.subscribe(s)
  override def onSubscribe(s: Subscription): Unit     = sub.onSubscribe(s)
  override def onNext(elem: A): Unit                  = sub.onNext(elem)
  override def onComplete(): Unit                     = sub.onComplete()
  override def onError(e: Throwable): Unit            = sub.onError(e)
}

private[impl] object RSCompliance {
  def verifyNonNull(value: AnyRef, subject: String, ruleNumber: String): Unit =
    if (value eq null)
      throw new NullPointerException(s"$subject must be non-null (see reactive-streams spec, rule $ruleNumber)")

  class IllegalRequestCountException
      extends IllegalArgumentException(
        "The number of elements requested must be > 0 (see reactive-streams spec, rule 3.9)")

}
