/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages

import org.scalacheck.Gen
import org.scalatest.Inspectors
import swave.testkit.TestError
import swave.testkit.gen.TestFixture
import swave.core._

final class FlattenSpec extends SyncPipeSpec with Inspectors {

  implicit val env = StreamEnv()
  implicit val config = PropertyCheckConfig(minSuccessful = 1000)

  implicit val integerInput = Gen.chooseNum(0, 999)

  "FlattenConcat" in check {
    testSetup
      .fixture(fd ⇒ fd.inputFromIterables(Gen.chooseNum(0, 3).flatMap(Gen.listOfN(_, fd.input[Int]))))
      .output[Int]
      .param(Gen.chooseNum(1, 3))
      .prop.from { (in, out, parallelism) ⇒
        import TestFixture.State._

        val allInputs = in :: in.elements.toList
        var expectedResultSize = out.scriptedSize

        in.spout
          .map(_.spout)
          .flattenConcat(parallelism)
          .drainTo(out.drain) shouldTerminate likeThis {
            case Cancelled ⇒ // inputs can be in any state
            case Completed ⇒ forAll(allInputs) { _.terminalState shouldBe Completed }
            case error @ Error(TestError) ⇒
              forAtLeast(1, allInputs) { _.terminalState shouldBe error }
              expectedResultSize = out.size
          }

        out.received shouldEqual in.elements.flatMap(_.produced).take(expectedResultSize)
      }
  }

  "FlattenMerge" in check {
    testSetup
      .fixture(fd ⇒ fd.inputFromIterables(nonOverlappingIntTestInputs(fd, 0, 3)))
      .output[Int]
      .param(Gen.chooseNum(1, 3))
      .prop.from { (in, out, parallelism) ⇒
        import TestFixture.State._

        val allInputs = in :: in.elements.toList
        var expectedResultSize = out.scriptedSize

        in.spout
          .map(_.spout)
          .flattenMerge(parallelism)
          .drainTo(out.drain) shouldTerminate likeThis {
            case Cancelled ⇒ // inputs can be in any state
            case Completed ⇒ forAll(allInputs) { _.terminalState shouldBe Completed }
            case error @ Error(TestError) ⇒
              forAtLeast(1, allInputs) { _.terminalState shouldBe error }
              expectedResultSize = out.size
          }

        // verify that we received the elements in the right order
        val received = out.received
        for (sub ← in.elements) {
          val produced = sub.produced.filter(received.contains).distinct
          received.filter(produced.contains).distinct shouldEqual produced
        }
      }
  }
}
