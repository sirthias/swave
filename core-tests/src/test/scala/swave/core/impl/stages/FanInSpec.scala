/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import org.scalacheck.Gen
import org.scalatest.Inspectors
import swave.core.StreamEnv
import swave.testkit.TestError
import swave.testkit.gen.TestFixture

final class FanInSpec extends SyncPipeSpec with Inspectors {

  implicit val env    = StreamEnv()
  implicit val config = PropertyCheckConfig(minSuccessful = 1000)

  implicit val integerInput = Gen.chooseNum(0, 999)
  implicit val charInput    = Gen.alphaNumChar
  implicit val doubleInput  = Gen.posNum[Double]

  "Concat" in check {
    testSetup
      .fixtures(Gen.chooseNum(2, 4), _.input[Int])
      .output[Int]
      .prop.from { (ins, out) ⇒
      import TestFixture.State._

      val spouts             = ins.map(_.spout)
      var expectedResultSize = out.scriptedSize

      spouts.head
        .attachAll(spouts.tail)
        .fanInConcat
        .drainTo(out.drain) shouldTerminate likeThis {
        case Cancelled ⇒ // inputs can be in any state
        case Completed ⇒ forAll(ins) { _.terminalState shouldBe Completed }
        case error @ Error(TestError) ⇒
          forAtLeast(1, ins) { _.terminalState shouldBe error }
          expectedResultSize = out.size
      }

      out.received shouldEqual ins.flatMap(_.produced).take(expectedResultSize)
    }
  }

  "FirstNonEmpty" in check {
    testSetup
      .fixtures(Gen.chooseNum(2, 4), _.input[Int])
      .output[Int]
      .prop.from { (ins, out) ⇒
      import TestFixture.State._

      val spouts             = ins.map(_.spout)
      var expectedResultSize = out.scriptedSize

      spouts.head
        .attachAll(spouts.tail)
        .fanInFirstNonEmpty
        .drainTo(out.drain) shouldTerminate likeThis {
        case Cancelled ⇒ // inputs can be in any state
        case Completed ⇒
          forAll(ins.dropWhile(_.terminalState == Completed)) { in ⇒
            if (in.terminalState != Cancelled && in.scriptedSize > 0) fail()
          }
        case error @ Error(TestError) ⇒
          forAtLeast(1, ins) { _.terminalState shouldBe error }
          expectedResultSize = out.size
      }

      out.received shouldEqual ins.map(_.produced).find(_.nonEmpty).getOrElse(Nil).take(expectedResultSize)
    }
  }

  "Merge" in check {
    testSetup
      .fixture(fd ⇒ nonOverlappingIntTestInputs(fd, 2, 4))
      .output[Int]
      .prop.from { (ins, out) ⇒
      import TestFixture.State._

      val spouts             = ins.map(_.spout)
      var expectedResultSize = out.scriptedSize

      spouts.head
        .attachAll(spouts.tail)
        .fanInMerge()
        .drainTo(out.drain) shouldTerminate likeThis {
        case Cancelled ⇒ // inputs can be in any state
        case Completed ⇒ forAll(ins) { _.terminalState shouldBe Completed }
        case error @ Error(TestError) ⇒
          forAtLeast(1, ins) { _.terminalState shouldBe error }
          expectedResultSize = out.size
      }

      // verify that we received the elements in the right order
      val received = out.received
      for (in ← ins) {
        val produced = in.produced.filter(received.contains).distinct
        received.filter(produced.contains).distinct shouldEqual produced
      }
    }
  }

  "ToTuple" in check {
    testSetup
      .input[Int]
      .input[Char]
      .input[Double]
      .output[(Int, Char, Double)]
      .prop.from {
      (inInt, inChar, inDouble, out) ⇒
        import TestFixture.State._

        val inputs             = inInt :: inChar :: inDouble :: Nil
        var expectedResultSize = out.scriptedSize

        inInt.spout
          .attach(inChar.spout)
          .attach(inDouble.spout)
          .fanInToTuple
          .drainTo(out.drain) shouldTerminate likeThis {
          case Cancelled | Completed ⇒ // inputs can be in any state
          case error @ Error(TestError) ⇒
            forAtLeast(1, inputs) { _.terminalState shouldBe error }
            expectedResultSize = out.size
        }

        out.received shouldEqual inInt.produced
          .zip(inChar.produced)
          .zip(inDouble.produced)
          .map({ case ((i, c), d) ⇒ (i, c, d) })
          .take(expectedResultSize)
    }
  }
}
