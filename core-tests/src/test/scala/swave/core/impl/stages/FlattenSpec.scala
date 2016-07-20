/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages

import org.scalacheck.Gen
import org.scalatest.Inspectors
import swave.core._
import swave.testkit.TestError
import swave.testkit.gen.TestFixture

final class FlattenSpec extends SyncPipeSpec with Inspectors {

  implicit val env = StreamEnv()
  implicit val config = PropertyCheckConfig(minSuccessful = 1000)

  implicit val integerInput = Gen.chooseNum(0, 999)

  "FlattenConcat" in check {
    testSetup
      .fixture(fd ⇒ fd.inputFromIterables(Gen.chooseNum(0, 3).flatMap(Gen.listOfN(_, fd.input[Int]))))
      .output[Int]
      .prop.from { (in, out) ⇒
        import TestFixture.State._

        val allInputs = in :: in.elements.toList
        var expectedResultSize = out.scriptedSize

        in.spout
          .map(_.spout)
          .flattenConcat()
          .drainTo(out.drain) shouldTerminate likeThis {
            case Cancelled ⇒ // inputs can be in any state
            case Completed ⇒ forAll(allInputs) { _.terminalState shouldBe Completed }
            case error @ Error(TestError) ⇒
              forAtLeast(1, allInputs) { _.terminalState shouldBe error }
              expectedResultSize = out.size
          }

        out.received shouldEqual allInputs.tail.flatMap(_.produced).take(expectedResultSize)
      }
  }
}
