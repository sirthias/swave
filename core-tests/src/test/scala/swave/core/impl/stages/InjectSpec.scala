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
import swave.testkit.gen.{ TestSetup, TestOutput, TestFixture, TestError }

import scala.collection.mutable.ListBuffer

final class InjectSpec extends PipeSpec with Inspectors {

  implicit val env = StreamEnv()
  implicit val config = PropertyCheckConfig(minSuccessful = 1000)

  implicit val integerInput = Gen.chooseNum(0, 999)

  "Inject" in check {
    testSetup
      .input[Int]
      .output[Stream[Int]]
      .fixture(fd ⇒ Gen.listOfN(10, fd.output[Int](TestSetup.Default.nonDroppingOutputScripts)))
      .prop
      .from { (in, out, allSubOuts) ⇒
        import TestFixture.State._

        val iter = allSubOuts.iterator
        val subOuts = ListBuffer.empty[TestOutput[Int]]
        out.appendElemHandler { sub ⇒
          if (iter.hasNext) {
            val subOut = iter.next()
            subOuts += subOut
            sub.drainTo(subOut.drain)
          } else sub.drainTo(Drain.ignore)
        }

        in.stream
          .inject()
          .drainTo(out.drain) shouldTerminate likeThis {
            case Cancelled ⇒ // input can be in any state

            case Completed if subOuts.nonEmpty ⇒
              forAll(subOuts.init) { _.terminalState shouldBe Cancelled }
              subOuts.last.terminalState should (be(Cancelled) or be(Completed))

            case Completed ⇒ in.scriptedSize shouldBe 0

            case error @ Error(TestError) ⇒
              if (subOuts.nonEmpty) {
                forAll(subOuts.init) { _.terminalState shouldBe Cancelled }
                subOuts.last.terminalState should (be(Cancelled) or be(error))
              }
              in.terminalState should (be(Cancelled) or be(error))
          }

        subOuts.flatMap(_.received) shouldEqual in.produced.take(subOuts.map(_.size).sum)
      }
  }
}