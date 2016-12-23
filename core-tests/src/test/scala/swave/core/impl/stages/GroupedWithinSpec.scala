/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import scala.concurrent.duration._
import scala.util.Success
import org.scalatest.FreeSpec
import swave.core.util._
import swave.core._
import swave.testkit.Probes._

/**
  * Almost directly transcribed from akka-stream's FlowGroupedWithinSpec which carries this copyright:
  *
  *    Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
  */
final class GroupedWithinSpec extends FreeSpec with StreamEnvShutdown {

  implicit val env = StreamEnv()

  "GroupedWithin must" - {

    "group elements within the duration" taggedAs NotOnTravis in {
      val input = Iterator.from(1)
      val spout = SpoutProbe[Int]
      val drain = DrainProbe[Seq[Int]]

      spout.groupedWithin(1000, 80.millis).drainTo(drain) shouldBe a[Success[_]]

      drain.sendRequest(100)
      val demand1 = spout.expectRequestAggregated(20.millis).toInt
      demand1.times { spout.rawSendNext(input.next()) }
      val demand2 = spout.expectRequestAggregated(20.millis).toInt
      demand2.times { spout.rawSendNext(input.next()) }
      val demand3 = spout.expectRequestAggregated(100.millis).toInt

      drain.expectNext((1 to (demand1 + demand2)).toVector)

      spout.expectNoSignal(100.millis)
      demand3.times { spout.rawSendNext(input.next()) }
      drain.expectNext(Seq(env.settings.maxBatchSize * 2 + 1)) // the kicker
      drain.expectNext(((demand1 + demand2 + 2) to (demand1 + demand2 + demand3)).toVector)

      spout.sendComplete()
      drain.expectComplete()
      drain.verifyCleanStop()
    }

    "deliver buffered elements onComplete before the timeout" in {
      val drain = DrainProbe[Seq[Int]]
      Spout(1 to 3).groupedWithin(1000, 10.second).drainTo(drain)
      drain.sendRequest(100)
      drain.expectNext((1 to 3).toList)
      drain.expectComplete()
      drain.verifyCleanStop()
    }

    "buffer groups until requested from downstream" in {
      val input = Iterator.from(1)
      val spout = SpoutProbe[Int]
      val drain = DrainProbe[Seq[Int]]

      spout.groupedWithin(1000, 100.millis).drainTo(drain)

      drain.sendRequest(1)
      val demand1 = spout.expectRequest().toInt
      demand1.times { spout.rawSendNext(input.next()) }
      drain.expectNext((1 to demand1).toVector)
      val demand2 = spout.expectRequest().toInt
      demand2.times { spout.rawSendNext(input.next()) }
      drain.expectNoSignal(200.millis)
      drain.sendRequest(1)
      drain.expectNext(((demand1 + 1) to (demand1 + demand2)).toVector)
      spout.sendComplete()
      drain.expectComplete()
      drain.verifyCleanStop()
    }

    "drop empty groups and produce 'kicker' elements" in {
      val spout = SpoutProbe[Int]
      val drain = DrainProbe[Seq[Int]]

      spout.groupedWithin(1000, 100.millis).drainTo(drain)

      drain.sendRequest(3)
      drain.expectNoSignal(150.millis)
      spout.sendNext(1, 2, 3)
      drain.expectNext(Seq(1))    // the kicker
      drain.expectNext(Seq(2, 3)) // the kicker
      drain.expectNoSignal(150.millis)
      drain.sendRequest(1)
      spout.sendComplete()
      drain.expectComplete()
      drain.verifyCleanStop()
    }

    "reset time window when max elements reached" in {
      val spout = SpoutProbe[Int]
      val drain = DrainProbe[Seq[Int]]

      spout.groupedWithin(3, 200.millis).drainTo(drain)

      drain.sendRequest(2)
      drain.expectNoSignal(100.millis)
      spout.sendNext(1 to 4)
      drain.expectNext(List(1, 2, 3))
      drain.expectNoSignal(150.millis)
      within(100.millis) { drain.expectNext(List(4)) }
      spout.sendComplete()
      drain.expectComplete()
      drain.verifyCleanStop()
    }
  }
}
