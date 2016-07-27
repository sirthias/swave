/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages

import scala.util.Success
import org.scalatest.FreeSpec
import scala.concurrent.duration._
import swave.testkit.Probes
import swave.core.{ NotOnTravis, StreamEnvShutdown, StreamEnv }
import swave.core.util._
import Probes._

final class DropWithinSpec extends FreeSpec with StreamEnvShutdown {

  implicit val env = StreamEnv()

  "DropWithin must" - {

    "deliver elements after the duration, but not before" taggedAs NotOnTravis in {
      val input = Iterator.from(1)
      val spout = SpoutProbe[Int]
      val drain = DrainProbe[Int]

      spout.dropWithin(100.millis).drainTo(drain) shouldBe a[Success[_]]

      drain.sendRequest(100)
      val demand1 = spout.expectRequestAggregated(20.millis)
      demand1.toInt.times { spout.sendNext(input.next()) }
      val demand2 = spout.expectRequestAggregated(20.millis)
      demand2.toInt.times { spout.sendNext(input.next()) }
      val demand3 = spout.expectRequestAggregated(100.millis)

      spout.expectNoSignal()
      demand3.toInt.times { spout.sendNext(input.next()) }
      ((demand1 + demand2 + 1).toInt to (demand1 + demand2 + demand3).toInt).foreach(drain.expectNext(_))

      spout.sendComplete()
      drain.expectComplete()
    }

    "deliver completion even before the duration" taggedAs NotOnTravis in {
      val spout = SpoutProbe[Int]
      val drain = DrainProbe[Int]

      spout.dropWithin(1.second).drainTo(drain) shouldBe a[Success[_]]

      spout.sendComplete()
      drain.expectComplete()
      drain.verifyCleanStop()
    }
  }

}
