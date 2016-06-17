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
import swave.core.{ StreamLimitExceeded, Overflow, StreamEnv }
import swave.testkit.gen.TestFixture

final class SimpleOpSpec extends SyncPipeSpec with Inspectors {

  implicit val env = StreamEnv()
  implicit val config = PropertyCheckConfig(minSuccessful = 1000)

  implicit val integerInput = Gen.chooseNum(0, 999)

  "BufferBackpressure" in check {
    testSetup
      .input[Int]
      .output[Int]
      .param(Gen.chooseNum(0, 16))
      .prop.from { (in, out, param) ⇒

        in.stream
          .buffer(param)
          .drainTo(out.drain) shouldTerminate asScripted(in)

        out.received shouldEqual in.produced.take(out.size)
      }
  }

  "BufferDropping" in check {
    testSetup
      .input[Int]
      .output[Int]
      .param(Gen.oneOf(Overflow.DropHead, Overflow.DropTail, Overflow.DropBuffer, Overflow.DropNew))
      .prop.from { (in, out, param) ⇒

        in.stream
          .buffer(4, param)
          .drainTo(out.drain) shouldTerminate asScripted(in)
      }
  }

  "Collect" in check {
    testSetup
      .input[Int]
      .output[Int]
      .prop.from { (in, out) ⇒

        in.stream
          .collect { case x if x < 100 ⇒ x * 2 }
          .drainTo(out.drain) shouldTerminate asScripted(in)

        out.received shouldEqual in.produced.collect({ case x if x < 100 ⇒ x * 2 }).take(out.scriptedSize)
      }
  }

  "Conflate" in check {
    testSetup
      .input[Int]
      .output[Vector[Int]]
      .prop.from { (in, out) ⇒

        in.stream
          .conflateWithSeed(Vector(_))(_ :+ _)
          .drainTo(out.drain) shouldTerminate asScripted(in)

        val res = out.received.flatten
        res shouldEqual in.produced.take(res.size)
      }
  }

  "Deduplicate" in check {
    testSetup
      .input[Int](Gen.chooseNum(1, 5))
      .output[Int]
      .prop.from { (in, out) ⇒

        in.stream
          .deduplicate
          .drainTo(out.drain) shouldTerminate asScripted(in)

        out.received shouldEqual in.produced
          .scanLeft(0)((last, x) ⇒ if (x == math.abs(last)) -x else x)
          .filter(_ > 0)
          .take(out.scriptedSize)
      }
  }

  "Drop" in check {
    testSetup
      .input[Int]
      .output[Int]
      .param(Gen.chooseNum(0, 200))
      .prop.from { (in, out, param) ⇒

        in.stream
          .dropWhile(_ < param)
          .drainTo(out.drain) shouldTerminate asScripted(in)

        out.received shouldEqual in.produced.dropWhile(_ < param).take(out.scriptedSize)
      }
  }

  "DropLast" in check {
    testSetup
      .input[Int]
      .output[Int]
      .param(Gen.chooseNum(0, 5))
      .prop.from { (in, out, param) ⇒

        in.stream
          .dropLast(param)
          .drainTo(out.drain) shouldTerminate asScripted(in)

        out.received shouldEqual in.produced.dropRight(param).take(out.scriptedSize)
      }
  }

  "DropWhile" in check {
    testSetup
      .input[Int]
      .output[Int]
      .param(Gen.chooseNum(0, 5))
      .prop.from { (in, out, param) ⇒

        in.stream
          .dropLast(param)
          .drainTo(out.drain) shouldTerminate asScripted(in)

        out.received shouldEqual in.produced.dropRight(param).take(out.scriptedSize)
      }
  }

  "Filter" in check {
    testSetup
      .input[Int]
      .output[Int]
      .param(Gen.chooseNum(0, 500))
      .prop.from { (in, out, param) ⇒

        in.stream
          .filter(_ < param)
          .drainTo(out.drain) shouldTerminate asScripted(in)

        out.received shouldEqual in.produced.filter(_ < param).take(out.scriptedSize)
      }
  }

  "Fold" in check {
    testSetup
      .input[Int]
      .output[Int]
      .prop.from { (in, out) ⇒

        in.stream
          .fold(0)(_ + _)
          .drainTo(out.drain) shouldTerminate asScripted(in)

        if (out.size > 0) out.received shouldEqual List(in.produced.sum)
      }
  }

  "Map" in check {
    testSetup
      .input[Int]
      .output[String]
      .prop.from { (in, out) ⇒

        in.stream
          .map(_.toString)
          .drainTo(out.drain) shouldTerminate asScripted(in)

        out.received shouldEqual in.produced.take(out.scriptedSize).map(_.toString)
      }
  }

  "Grouped" in check {
    testSetup
      .input[Int]
      .output[Seq[Int]]
      .param(Gen.chooseNum(1, 10))
      .prop.from { (in, out, param) ⇒

        in.stream
          .grouped(param)
          .drainTo(out.drain) shouldTerminate asScripted(in)

        out.received shouldEqual in.produced.grouped(param).take(out.size).toList
      }
  }

  "GroupedToCellArray" in check {
    testSetup
      .input[Int]
      .output[List[Int]]
      .param(Gen.chooseNum(1, 10))
      .prop.from { (in, out, param) ⇒

        in.stream
          .groupedToCellArray(param)
          .map(_.toSeq[List])
          .drainTo(out.drain) shouldTerminate asScripted(in)

        out.received shouldEqual in.produced.grouped(param).take(out.size).toList
      }
  }

  "Limit" in check {
    testSetup
      .input[Int]
      .output[Int]
      .param(Gen.chooseNum(0, 5))
      .prop.from { (in, out, param) ⇒

        def pipeline =
          in.stream
            .limit(param.toLong)
            .drainTo(out.drain)

        if (scriptedElementCount(in, out) <= param) {
          pipeline shouldTerminate asScripted(in)
          out.received shouldEqual in.produced.take(out.scriptedSize)
        } else {
          pipeline shouldTerminate withErrorLike { case StreamLimitExceeded(`param`, _) ⇒ }
        }
      }
  }

  "Take" in check {
    testSetup
      .input[Int]
      .output[Int]
      .param(Gen.chooseNum(0, 10))
      .prop.from { (in, out, param) ⇒
        import TestFixture.State._

        in.stream
          .take(param.toLong)
          .drainTo(out.drain) shouldTerminate {
            case Cancelled | Completed ⇒ // any input state could end up here
            case error                 ⇒ in.terminalState shouldBe error
          }

        out.received shouldEqual in.produced.take(math.min(param, out.scriptedSize))
      }
  }

  "Scan" in check {
    testSetup
      .input[Int]
      .output[Double]
      .prop.from { (in, out) ⇒

        in.stream
          .scan(4.2)(_ + _)
          .drainTo(out.drain) shouldTerminate asScripted(in)

        val expected = in.produced.scanLeft(4.2)(_ + _).take(out.scriptedSize)
        if (in.scriptedError.isEmpty) out.received shouldEqual expected
        else out.received shouldEqual expected.take(out.received.size)
      }
  }
}