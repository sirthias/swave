/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import scala.collection.mutable.ListBuffer
import swave.testkit.Probes._

class DrainSpec extends SwaveSpec {

  implicit val env = StreamEnv()

  "Drains should work as expected" - {

    "Drain.cancelling" in {
      val probe = SpoutProbe[Int]
      probe.drainTo(Drain.cancelling)
      probe.expectCancel()
    }

    "Drain.first" in {
      Spout(1, 2, 3).drainTo(Drain.first(4)).value.get.get shouldEqual List(1, 2, 3)
      Spout(1, 2, 3).drainTo(Drain.first(3)).value.get.get shouldEqual List(1, 2, 3)
      Spout(1, 2, 3).drainTo(Drain.first(2)).value.get.get shouldEqual List(1, 2)
      Spout(1, 2, 3).drainTo(Drain.first(1)).value.get.get shouldEqual List(1)
      the[IllegalArgumentException] thrownBy Drain.first(0) should have message "`groupSize` must be > 0"
    }

    "Drain.foreach" in {
      val buf   = new ListBuffer[Int]
      def drain = Drain.foreach[Int](buf += _)

      Spout.empty[Int].drainTo(drain)
      buf shouldBe empty

      Spout.one(1).drainTo(drain)
      buf shouldEqual List(1)

      Spout(2, 3).drainTo(drain)
      buf shouldEqual List(1, 2, 3)

      Spout(1, 0, 3).foreach(1 / _).failed.value.get.get shouldBe an[ArithmeticException]
    }

    "Drain.fold" in {
      Spout(1, 2, 3).drainFolding(0)(_ + _).value.get.get shouldEqual 6
      Spout.empty[Int].drainFolding(0)(_ + _).value.get.get shouldEqual 0
      Spout(1, 0, 3).drainFolding(0)(_ / _).failed.value.get.get shouldBe an[ArithmeticException]
    }

    "Drain.headOption" in {
      Spout.empty[Int].drainTo(Drain.headOption).value.get.get shouldEqual None
      Spout.one(1).drainTo(Drain.headOption).value.get.get shouldEqual Some(1)
      Spout(1, 2).drainTo(Drain.headOption).value.get.get shouldEqual Some(1)
    }
  }
}
