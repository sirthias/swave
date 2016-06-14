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

package swave.core.tck

import org.reactivestreams.Publisher
import org.reactivestreams.tck.{ PublisherVerification, TestEnvironment }
import org.scalatest.testng.TestNGSuiteLike
import org.testng.SkipException
import swave.core._

abstract class SwavePublisherVerification[T](val testEnv: TestEnvironment, publisherShutdownTimeout: Long)
    extends PublisherVerification[T](testEnv, publisherShutdownTimeout)
    with TestNGSuiteLike with StreamEnvShutdown {

  def this(printlnDebug: Boolean) =
    this(new TestEnvironment(Timeouts.defaultTimeout.toMillis, printlnDebug), Timeouts.publisherShutdownTimeout.toMillis)

  def this() = this(false)

  override def createFailedPublisher(): Publisher[T] =
    Stream.failing[T](new Exception("Nope")).drainTo(Drain.toPublisher()).get

  override def required_spec313_cancelMustMakeThePublisherEventuallyDropAllReferencesToTheSubscriber(): Unit =
    throw new SkipException("Not relevant for publisher w/o fanout support")
}
