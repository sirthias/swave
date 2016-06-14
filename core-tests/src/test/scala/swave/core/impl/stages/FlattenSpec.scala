/*
 * Copyright © 2016 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swave.core.impl.stages

import org.scalacheck.Gen
import org.scalatest.Inspectors
import swave.core._
import swave.testkit.gen.{ TestFixture, TestError }

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

        in.stream
          .map(_.stream)
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