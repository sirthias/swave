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
import swave.core.StreamEnv
import swave.testkit.gen.TestFixture

final class FanOutSpec extends SyncPipeSpec with Inspectors {

  implicit val env = StreamEnv()
  implicit val config = PropertyCheckConfig(minSuccessful = 1000)

  implicit val integerInput = Gen.chooseNum(0, 999)

  "FanOutBroadcast" in check {
    testSetup
      .input[Int]
      .fixtures(Gen.chooseNum(1, 3), _.output[Int])
      .prop.from { (in, outs) ⇒
        import TestFixture.State._

        in.stream.fanOutBroadcast()
          .subDrains(outs.tail.map(_.drain.dropResult))
          .subContinue
          .drainTo(outs.head.drain)

        in.terminalState match {
          case Cancelled ⇒ forAll(outs) { _.terminalState shouldBe Cancelled }
          case Completed ⇒ forAll(outs) { _.terminalState should (be(Cancelled) or be(Completed)) }
          case error     ⇒ forAll(outs) { _.terminalState should (be(error) or be(Cancelled)) }
        }

        forAll(outs) { out ⇒ out.received shouldEqual in.produced.take(out.scriptedSize) }
      }
  }
}