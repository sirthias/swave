/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages

import scala.concurrent.{ Promise, Future }
import swave.testkit.TestError
import swave.testkit.Probes._
import swave.core._

class FutureSourceSpec extends SwaveSpec {

  implicit val env = StreamEnv()

  "Stream.fromFuture" - {

    "already completed success" in {
      Stream(Future.successful(42))
        .drainTo(DrainProbe[Int]).get
        .sendRequest(5)
        .expectNext(42)
        .expectComplete()
        .verifyCleanStop()
    }

    "already completed failure" in {
      Stream(Future.failed(TestError))
        .drainTo(DrainProbe[Int]).get
        .expectError(TestError)
        .verifyCleanStop()
    }

    "externally completed (request before completion)" in {
      val promise = Promise[Int]
      val probe = Stream(promise.future).drainTo(DrainProbe[Int]).get.sendRequest(5)
      Thread.sleep(10)
      promise.success(42)
      probe
        .expectNext(42)
        .expectComplete()
        .verifyCleanStop()
    }

    "externally completed (completion before request)" in {
      val promise = Promise[Int]
      val probe = Stream(promise.future).drainTo(DrainProbe[Int]).get
      Thread.sleep(10)
      promise.success(42)
      probe
        .sendRequest(5)
        .expectNext(42)
        .expectComplete()
        .verifyCleanStop()
    }

    "externally completed with failure" in {
      val promise = Promise[Int]
      val probe = Stream(promise.future).drainTo(DrainProbe[Int]).get
      Thread.sleep(10)
      promise.failure(TestError)
      probe
        .expectError(TestError)
        .verifyCleanStop()
    }
  }
}
