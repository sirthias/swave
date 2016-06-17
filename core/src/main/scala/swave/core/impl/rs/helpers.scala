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

package swave.core.impl.rs

import org.reactivestreams._
import swave.core.impl.stages.Stage
import swave.core.util._

private[impl] class SyncSubscription extends Subscription {
  var cancelled = false
  var requested = 0L

  def request(n: Long) = {
    requested ⊹= n
  }

  def cancel() = cancelled = true
}

private[impl] class ForwardToRunnerSubscription(stage: Stage) extends Subscription {
  def request(n: Long) =
    if (n > 0) stage.runner.enqueueRequest(stage, n)(stage)
    else stage.runner.enqueueXEvent(stage, ForwardToRunnerSubscription.IllegalRequest(n))
  def cancel() = stage.runner.enqueueCancel(stage)(stage)
}

private[impl] object ForwardToRunnerSubscription {
  final case class IllegalRequest(n: Long)
}

private[core] class SubPubProcessor[A, B](sub: Subscriber[A], pub: Publisher[B]) extends Processor[A, B] {
  override def subscribe(s: Subscriber[_ >: B]): Unit = pub.subscribe(s)
  override def onSubscribe(s: Subscription): Unit = sub.onSubscribe(s)
  override def onNext(elem: A): Unit = sub.onNext(elem)
  override def onComplete(): Unit = sub.onComplete()
  override def onError(e: Throwable): Unit = sub.onError(e)
}

private[impl] class ForwardToRunnerPublisher(stage: Stage) extends Publisher[AnyRef] {
  def subscribe(subscriber: Subscriber[_ >: AnyRef]) = {
    if (subscriber eq null)
      throw new NullPointerException("Subscriber must be non-null (see reactive-streams spec, rule 1.9)")
    stage.runner.enqueueXEvent(stage, subscriber)
  }
}

class ForwardToRunnerSubscriber(stage: Stage) extends Subscriber[AnyRef] {
  def onSubscribe(s: Subscription) = {
    if (s eq null) throw new NullPointerException("Subscription must be non-null (see reactive-streams spec, rule 2.13)")
    stage.runner.enqueueXEvent(stage, s)
  }
  def onNext(elem: AnyRef) = {
    if (elem eq null) throw new NullPointerException("Element must be non-null (see reactive-streams spec, rule 2.13)")
    stage.runner.enqueueOnNext(stage, elem)(stage)
  }

  def onComplete() = stage.runner.enqueueOnComplete(stage)(stage)

  def onError(e: Throwable) = {
    if (e eq null) throw new NullPointerException("Throwable must be non-null (see reactive-streams spec, rule 2.13)")
    stage.runner.enqueueOnError(stage, e)(stage)
  }
}