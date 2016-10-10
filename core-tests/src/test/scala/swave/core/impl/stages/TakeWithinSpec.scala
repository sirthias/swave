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
import swave.core.{NotOnTravis, StreamEnv, StreamEnvShutdown}
import swave.testkit.Probes._

final class TakeWithinSpec extends FreeSpec with StreamEnvShutdown {

  implicit val env = StreamEnv()

  "TakeWithin must" - {

    "deliver elements before the timeout, but not after" taggedAs NotOnTravis in {
      val input = Iterator.from(1)
      val spout = SpoutProbe[Int]
      val drain = DrainProbe[Int]

      spout.takeWithin(100.millis).drainTo(drain) shouldBe a[Success[_]]

      drain.sendRequest(100)
      val demand1 = spout.expectRequestAggregated(20.millis).toInt
      demand1.times { spout.rawSendNext(input.next()) }
      val demand2 = spout.expectRequestAggregated(20.millis).toInt
      demand2.times { spout.rawSendNext(input.next()) }
      val demand3 = spout.expectRequestAggregated(100.millis).toInt

      spout.expectNoSignal()
      demand3.toInt.times { spout.rawSendNext(input.next()) }
      (1 to (demand1 + demand2)).foreach(drain.expectNext(_))

      spout.sendComplete()
      drain.expectComplete()
    }

    "deliver completion even before the duration" taggedAs NotOnTravis in {
      val spout = SpoutProbe[Int]
      val drain = DrainProbe[Int]

      spout.takeWithin(1.second).drainTo(drain) shouldBe a[Success[_]]

      spout.sendComplete()
      drain.expectComplete()
      drain.verifyCleanStop()
    }
  }

}
