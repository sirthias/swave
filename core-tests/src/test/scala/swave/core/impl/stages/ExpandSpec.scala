/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import java.util.concurrent.ThreadLocalRandom
import swave.testkit.TestError

import scala.util.Try
import scala.concurrent.duration._
import swave.core._
import swave.core.util._
import swave.testkit.Probes._

/**
  * Partially transcribed from akka-stream's FlowExpandSpec which carries this copyright:
  *
  *    Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
  */
final class ExpandSpec extends SwaveSpec {

  implicit val env = StreamEnv()

  "Expand must" - {

    implicit class ExpandTo(underlying: SpoutProbe[Int]) extends AnyRef {
      def expandTo(drain: Drain[Int, DrainProbe[Int]]): Try[DrainProbe[Int]] =
        underlying.expand(Iterator(1, 2), Iterator.continually(_)).drainTo(drain)
    }

    "drain the zero Iterator before working as expected" in {
      val upstream   = SpoutProbe[Int]
      val downstream = DrainProbe[Int]
      upstream.expandTo(downstream)

      upstream.expectRequest(1)
      downstream.requestExpectNext(1, 2)
      downstream.expectNoSignal()

      upstream.sendNext(42)
      downstream.requestExpectNext(42, 42, 42)

      upstream.sendNext(24)
      downstream.requestExpectNext(24, 24)

      downstream.sendCancel()
      upstream.expectRequest(1)
      upstream.expectCancel()
      upstream.verifyCleanStop()
    }

    "work properly when the zero Iterator is not pulled" in {
      val upstream   = SpoutProbe[Int]
      val downstream = DrainProbe[Int]
      upstream.expandTo(downstream)

      upstream.sendNext(42)
      downstream.requestExpectNext(42, 42, 42)

      upstream.sendNext(24)
      downstream.requestExpectNext(24, 24)

      downstream.sendCancel()
      upstream.expectRequest(1)
      upstream.expectCancel()
      upstream.verifyCleanStop()
    }

    "pass-through elements unchanged when there is no rate difference" in {
      val upstream   = SpoutProbe[Int]
      val downstream = DrainProbe[Int]
      upstream.expandTo(downstream)

      for (i ← 50 to 100) {
        // Order is important here: If the request comes first it will be extrapolated!
        upstream.sendNext(i)
        downstream.requestExpectNext(i)
      }

      upstream.sendComplete()
      downstream.expectComplete()
      upstream.verifyCleanStop()
    }

    "work on a variable rate chain" in {
      Spout(50 to 100).map { i ⇒
        if (ThreadLocalRandom.current().nextBoolean()) Thread.sleep(10); i
      }.expand(Iterator.continually(_))
        .drainFolding(Set.empty[Int])(_ + _)
        .await(1.second)
        .toSeq
        .sorted shouldEqual (50 to 100)
    }

    "backpressure publisher when subscriber is slower" in {
      val upstream   = SpoutProbe[Int]
      val downstream = DrainProbe[Int]
      upstream.expandTo(downstream)

      upstream.expectRequest(1)
      upstream.sendNext(1)
      downstream.requestExpectNext(1)
      downstream.requestExpectNext(1)

      upstream.expectRequest(1)
      upstream.sendNext(2)
      upstream.expectNoSignal()

      downstream.requestExpectNext(2)

      upstream.expectRequest(1)
      upstream.sendNext(3)
      upstream.expectNoSignal()

      downstream.sendCancel()
      upstream.expectCancel()
      upstream.verifyCleanStop()
    }

    "properly propagate exceptions thrown by zero Iterator" in {
      val upstream   = SpoutProbe[Int]
      val downstream = DrainProbe[Int]

      upstream
        .expand(Iterator(1, 2) ++ Iterator.continually(throw TestError), Iterator.continually(_))
        .drainTo(downstream)

      upstream.expectRequest(1)
      downstream.requestExpectNext(1, 2)
      downstream.expectNoSignal()

      downstream.sendRequest(1)
      downstream.expectError(TestError)

      upstream.expectCancel()
      upstream.verifyCleanStop()
    }

    "properly propagate exceptions thrown by extrapolation function" in {
      val upstream   = SpoutProbe[Int]
      val downstream = DrainProbe[Int]

      upstream.expand(x ⇒ if (x <= 2) Iterator.single(x) else throw TestError).drainTo(downstream)

      upstream.sendNext(1)
      downstream.requestExpectNext(1)
      upstream.sendNext(2)
      downstream.requestExpectNext(2)
      upstream.sendNext(3)
      downstream.expectError(TestError)

      upstream.expectCancel()
      upstream.verifyCleanStop()
    }

    "properly propagate exceptions thrown by extrapolate Iterator" in {
      val upstream   = SpoutProbe[Int]
      val downstream = DrainProbe[Int]

      upstream.expand(_ ⇒ Iterator(1, 2) ++ Iterator.continually(throw TestError)).drainTo(downstream)

      upstream.sendNext(42)
      downstream.requestExpectNext(1, 2)
      upstream.expectRequest(1)

      downstream.sendRequest(1)
      downstream.expectError(TestError)

      upstream.expectCancel()
      upstream.verifyCleanStop()
    }
  }
}
