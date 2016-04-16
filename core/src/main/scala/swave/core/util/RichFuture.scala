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

package swave.core.util

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise, Future }
import swave.core.StreamEnv

final class RichFuture[T](val underlying: Future[T]) extends AnyVal {
  def fast: FastFuture[T] = new FastFuture[T](underlying)

  def await(timeout: FiniteDuration = 10.seconds): T =
    if (timeout == Duration.Zero) {
      underlying.value match {
        case Some(t) ⇒ t.get
        case None    ⇒ throw new TimeoutException(s"Future was not completed")
      }
    } else Await.result(underlying, timeout)

  def delay(duration: FiniteDuration)(implicit env: StreamEnv): Future[T] = {
    import env.defaultDispatcher
    val promise = Promise[T]()
    underlying.onComplete { value ⇒
      env.scheduler.scheduleOnce(duration) { promise.complete(value); () }
    }
    promise.future
  }
}
