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

import java.util.concurrent.{ TimeUnit, Executors, ExecutorService }
import org.reactivestreams.Publisher
import org.reactivestreams.tck.{ IdentityProcessorVerification, TestEnvironment }
import org.scalatest.testng.TestNGSuiteLike
import org.testng.SkipException
import org.testng.annotations.AfterClass
import swave.core._

abstract class SwaveIdentityProcessorVerification[T](val testEnv: TestEnvironment, publisherShutdownTimeout: Long)
    extends IdentityProcessorVerification[T](testEnv, publisherShutdownTimeout)
    with TestNGSuiteLike with StreamEnvShutdown {

  def this(printlnDebug: Boolean) =
    this(new TestEnvironment(Timeouts.defaultTimeout.toMillis, printlnDebug), Timeouts.publisherShutdownTimeout.toMillis)

  def this() = this(false)

  override def createFailedPublisher(): Publisher[T] =
    Stream.failing[T](new Exception("Nope")).drainTo(Drain.toPublisher()).get

  // Publishers created by swave don't support fanout by default
  override def maxSupportedSubscribers: Long = 1L

  override def required_spec313_cancelMustMakeThePublisherEventuallyDropAllReferencesToTheSubscriber(): Unit =
    throw new SkipException("Not relevant for publisher w/o fanout support")

  override lazy val publisherExecutorService: ExecutorService =
    Executors.newFixedThreadPool(3)

  @AfterClass
  def shutdownPublisherExecutorService(): Unit = {
    publisherExecutorService.shutdown()
    publisherExecutorService.awaitTermination(3, TimeUnit.SECONDS)
  }
}
