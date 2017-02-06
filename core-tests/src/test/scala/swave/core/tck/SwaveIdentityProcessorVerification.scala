/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.tck

import java.util.concurrent.{ExecutorService, Executors, TimeUnit}
import org.reactivestreams.Publisher
import org.reactivestreams.tck.{IdentityProcessorVerification, TestEnvironment}
import org.scalatest.testng.TestNGSuiteLike
import org.testng.SkipException
import org.testng.annotations.AfterClass
import swave.core._

abstract class SwaveIdentityProcessorVerification[T](val testEnv: TestEnvironment, publisherShutdownTimeout: Long)
    extends IdentityProcessorVerification[T](testEnv, publisherShutdownTimeout) with TestNGSuiteLike
    with StreamEnvShutdown {

  def this(printlnDebug: Boolean) =
    this(
      new TestEnvironment(Timeouts.defaultTimeout.toMillis, printlnDebug),
      Timeouts.publisherShutdownTimeout.toMillis)

  def this() = this(false)

  override def createFailedPublisher(): Publisher[T] =
    Spout.failing[T](new Exception("Nope")).drainTo(Drain.toPublisher()).get

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
