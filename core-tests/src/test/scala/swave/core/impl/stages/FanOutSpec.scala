/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import org.scalacheck.Gen
import org.scalatest.Inspectors
import swave.core.StreamEnv
import swave.testkit.gen.TestFixture

final class FanOutSpec extends SyncPipeSpec with Inspectors {

  implicit val env    = StreamEnv()
  implicit val config = PropertyCheckConfig(minSuccessful = 1000)

  implicit val integerInput = Gen.chooseNum(0, 999)

  "Broadcast" in check {
    testSetup
      .input[Int]
      .fixtures(Gen.chooseNum(1, 3), _.output[Int])
      .prop.from { (in, outs) ⇒
      import TestFixture.State._

      in.spout
        .fanOutBroadcast()
        .subDrains(outs.tail.map(_.drain.dropResult))
        .subContinue.drainTo(outs.head.drain)

      in.terminalState match {
        case Cancelled ⇒ forAll(outs) { _.terminalState shouldBe Cancelled }
        case Completed ⇒ forAll(outs) { _.terminalState should (be(Cancelled) or be(Completed)) }
        case error     ⇒ forAll(outs) { _.terminalState should (be(error) or be(Cancelled)) }
      }

      forAll(outs) { out ⇒
        out.received shouldEqual in.produced.take(out.scriptedSize)
      }
    }
  }

  "BroadcastBuffered" in check {
    testSetup
      .input[Int]
      .fixtures(Gen.chooseNum(1, 3), _.output[Int])
      .param(Gen.chooseNum(0, 16))
      .prop .from { (in, outs, bufferSize) ⇒
      import TestFixture.State._

      in.spout
        .fanOutBroadcastBuffered(bufferSize)
        .subDrains(outs.tail.map(_.drain.dropResult))
        .subContinue.drainTo(outs.head.drain)

      in.terminalState match {
        case Cancelled ⇒ forAll(outs) { _.terminalState shouldBe Cancelled }
        case Completed ⇒ forAll(outs) { _.terminalState should (be(Cancelled) or be(Completed)) }
        case error     ⇒ forAll(outs) { _.terminalState should (be(error) or be(Cancelled)) }
      }

      forAll(outs) { out ⇒
        out.received shouldEqual in.produced.take(out.size)
      }
    }
  }
}
