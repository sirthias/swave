/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages

import scala.util.Success
import scala.concurrent.duration._
import swave.testkit.TestError
import swave.testkit.Probes._
import swave.core.util.XorShiftRandom
import swave.core._

/**
  * Almost directly transcribed from akka-stream's FlowThrottleSpec which carries this copyright:
  *
  *    Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
  */
final class ThrottleSpec extends SwaveSpec {

  implicit val env = StreamEnv()

  "Throttle for single cost elements must" - {

    "work for the happy case" taggedAs NotOnTravis in {
      Spout(1 to 5)
        .throttle(1, 50.millis)
        .drainTo(DrainProbe[Int])
        .get
        .sendRequest(5)
        .expectNext(1, 2, 3, 4, 5)
        .expectComplete()
        .verifyCleanStop()
    }

    "accept very high rates" taggedAs NotOnTravis in {
      Spout(1 to 5)
        .throttle(1, 1.nanos)
        .drainTo(DrainProbe[Int])
        .get
        .sendRequest(5)
        .expectNext(1, 2, 3, 4, 5)
        .expectComplete()
        .verifyCleanStop()
    }

    "accept very low rates" taggedAs NotOnTravis in {
      Spout(1 to 5)
        .throttle(1, 100.days, burst = 1)
        .drainTo(DrainProbe[Int])
        .get
        .sendRequest(5)
        .expectNext(1)
        .expectNoSignal(20.millis)
        .sendCancel() // we won't wait 100 days, sorry
        .verifyCleanStop()
    }

    "emit single element per tick" taggedAs NotOnTravis in {
      val upstream   = SpoutProbe[Int]
      val downstream = DrainProbe[Int]

      upstream.throttle(1, 50.millis, burst = 0).drainTo(downstream) shouldBe a[Success[_]]

      downstream.sendRequest(20)
      upstream.sendNext(1)
      downstream.expectNoSignal(20.millis)
      downstream.expectNext(1)

      upstream.sendNext(2)
      downstream.expectNoSignal(20.millis)
      downstream.expectNext(2)

      upstream.sendComplete()
      downstream.expectComplete()
      downstream.verifyCleanStop()
    }

    "not send downstream if upstream does not emit element" taggedAs NotOnTravis in {
      val upstream   = SpoutProbe[Int]
      val downstream = DrainProbe[Int]
      upstream.throttle(1, 50.millis).drainTo(downstream) shouldBe a[Success[_]]

      downstream.sendRequest(2)
      upstream.sendNext(1)
      downstream.expectNext(1)

      downstream.expectNoSignal(50.millis)
      upstream.sendNext(2)
      downstream.expectNext(2)

      upstream.sendComplete()
      downstream.expectComplete()
      downstream.verifyCleanStop()
    }

    "cancel when downstream cancels" taggedAs NotOnTravis in {
      Spout(1 to 10).throttle(1, 50.millis).drainTo(DrainProbe[Int]).get.sendCancel().verifyCleanStop()
    }

    "send elements downstream as soon as the time comes" taggedAs NotOnTravis in {
      val probe = Spout(1 to 10).throttle(2, 120.millis, burst = 0).drainTo(DrainProbe[Int]).get.sendRequest(5)
      probe.receiveElementsWithin(150.millis) should be(1 to 2)
      probe
        .expectNoSignal(20.millis)
        .expectNext(3)
        .expectNoSignal(40.millis)
        .expectNext(4)
        .sendCancel()
        .verifyCleanStop()
    }

    "burst according to maximum if enough time has passed" taggedAs NotOnTravis in {
      val upstream   = SpoutProbe[Int]
      val downstream = DrainProbe[Int]
      upstream.throttle(1, 50.millis, burst = 5).drainTo(downstream) shouldBe a[Success[_]]

      // exhaust bucket first
      downstream.sendRequest(5)
      upstream.sendNext(1 to 5)
      downstream.receiveElementsWithin(100.millis, 5) should be(1 to 5)

      downstream.sendRequest(1)
      upstream.sendNext(6)
      downstream.expectNoSignal(30.millis)
      downstream.expectNext(6)
      downstream.sendRequest(5)
      downstream.expectNoSignal(250.millis)
      upstream.sendNext(7 to 11)
      downstream.receiveElementsWithin(100.millis, 5) should be(7 to 11)
      downstream.sendCancel().verifyCleanStop()
    }

    "burst some elements if have enough time" taggedAs NotOnTravis in {
      val upstream   = SpoutProbe[Int]
      val downstream = DrainProbe[Int]
      upstream.throttle(1, 50.millis, burst = 5).drainTo(downstream) shouldBe a[Success[_]]

      // Exhaust bucket first
      downstream.sendRequest(5)
      upstream.sendNext(1 to 5)
      downstream.receiveElementsWithin(100.millis, 5) should be(1 to 5)

      downstream.sendRequest(1)
      upstream.sendNext(6)
      downstream.expectNoSignal(30.millis)
      downstream.expectNext(6)
      downstream.expectNoSignal(120.millis)
      downstream.sendRequest(5)
      upstream.sendNext(7 to 10)
      downstream.receiveElementsWithin(100.millis, 2) should be(7 to 8)
      downstream.sendCancel().verifyCleanStop()
    }
  }

  "Throttle for various cost elements must" - {

    val random = XorShiftRandom()

    "emit elements according to cost" taggedAs NotOnTravis in {
      val list: List[String] = 2.to(8, step = 2).map(random.alphanumericString)(collection.breakOut)
      Spout(list)
        .throttle(2, 100.millis, 0, _.length)
        .drainTo(DrainProbe[String])
        .get
        .sendRequest(4)
        .expectNext(list.head)
        .expectNoSignal(150.millis)
        .expectNext(list(1))
        .expectNoSignal(250.millis)
        .expectNext(list(2))
        .expectNoSignal(350.millis)
        .expectNext(list(3))
        .expectComplete()
        .verifyCleanStop()
    }

    "handle rate calculation function exception" taggedAs NotOnTravis in {
      Spout(1 to 5)
        .throttle(2, 200.millis, 0, _ ⇒ throw TestError)
        .drainTo(DrainProbe[Int])
        .get
        .sendRequest(5)
        .expectError(TestError)
        .verifyCleanStop()
    }
  }
}
