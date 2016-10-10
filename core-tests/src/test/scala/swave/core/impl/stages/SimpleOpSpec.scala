/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages

import org.scalacheck.Gen
import org.scalatest.Inspectors
import swave.core.{Overflow, StreamEnv, StreamLimitExceeded}
import swave.testkit.TestError
import swave.testkit.gen.TestFixture

final class SimpleOpSpec extends SyncPipeSpec with Inspectors {

  implicit val env    = StreamEnv()
  implicit val config = PropertyCheckConfig(minSuccessful = 1000)

  implicit val integerInput = Gen.chooseNum(0, 999)
  implicit val stringInput  = Gen.listOfN(3, Gen.alphaNumChar).map(_.mkString)

  "BufferBackpressure" in check {
    testSetup.input[Int].output[Int].param(Gen.chooseNum(0, 16)).prop.from { (in, out, param) ⇒
      in.spout.buffer(param).drainTo(out.drain) shouldTerminate asScripted(in)

      out.received shouldEqual in.produced.take(out.size)
    }
  }

  "BufferDropping" in check {
    testSetup
      .input[Int]
      .output[Int]
      .param(Gen.oneOf(Overflow.DropHead, Overflow.DropTail, Overflow.DropBuffer, Overflow.DropNew))
      .prop
      .from { (in, out, param) ⇒
        in.spout.buffer(4, param).drainTo(out.drain) shouldTerminate asScripted(in)
      }
  }

  "Collect" in check {
    testSetup.input[Int].output[Int].prop.from { (in, out) ⇒
      in.spout.collect { case x if x < 100 ⇒ x * 2 }.drainTo(out.drain) shouldTerminate asScripted(in)

      out.received shouldEqual in.produced.collect({ case x if x < 100 ⇒ x * 2 }).take(out.scriptedSize)
    }
  }

  "Conflate" in check {
    testSetup.input[Int].output[Vector[Int]].prop.from { (in, out) ⇒
      in.spout.conflateWithSeed(Vector(_))(_ :+ _).drainTo(out.drain) shouldTerminate asScripted(in)

      val res = out.received.flatten
      res shouldEqual in.produced.take(res.size)
    }
  }

  "Deduplicate" in check {
    testSetup.input[Int](Gen.chooseNum(1, 5)).output[Int].prop.from { (in, out) ⇒
      in.spout.deduplicate.drainTo(out.drain) shouldTerminate asScripted(in)

      out.received shouldEqual in.produced
        .scanLeft(0)((last, x) ⇒ if (x == math.abs(last)) -x else x)
        .filter(_ > 0)
        .take(out.scriptedSize)
    }
  }

  "Drop" in check {
    testSetup.input[Int].output[Int].param(Gen.chooseNum(0, 5)).prop.from { (in, out, param) ⇒
      in.spout.drop(param.toLong).drainTo(out.drain) shouldTerminate asScripted(in)

      out.received shouldEqual in.produced.slice(param, param + out.scriptedSize)
    }
  }

  "DropLast" in check {
    testSetup.input[Int].output[Int].param(Gen.chooseNum(0, 5)).prop.from { (in, out, param) ⇒
      in.spout.dropLast(param).drainTo(out.drain) shouldTerminate asScripted(in)

      out.received shouldEqual in.produced.dropRight(param).take(out.scriptedSize)
    }
  }

  "DropWhile" in check {
    testSetup.input[Int].output[Int].param(Gen.chooseNum(0, 200)).prop.from { (in, out, param) ⇒
      in.spout.dropWhile(_ < param).drainTo(out.drain) shouldTerminate asScripted(in)

      out.received shouldEqual in.produced.dropWhile(_ < param).take(out.scriptedSize)
    }
  }

  "Filter" in check {
    testSetup.input[Int].output[Int].param(Gen.chooseNum(0, 500)).prop.from { (in, out, param) ⇒
      in.spout.filter(_ < param).drainTo(out.drain) shouldTerminate asScripted(in)

      out.received shouldEqual in.produced.filter(_ < param).take(out.scriptedSize)
    }
  }

  "Fold" in check {
    testSetup.input[Int].output[Int].prop.from { (in, out) ⇒
      import TestFixture.State._

      in.spout.fold(0)(_ + _).drainTo(out.drain) shouldTerminate {
        case Cancelled ⇒ // any input state could end up here
        case Completed ⇒ out.received shouldEqual List(in.produced.sum)
        case error     ⇒ in.terminalState shouldBe error
      }
    }
  }

  "Grouped" in check {
    testSetup.input[Int].output[Seq[Int]].param(Gen.chooseNum(1, 10)).prop.from { (in, out, param) ⇒
      in.spout.grouped(param).drainTo(out.drain) shouldTerminate asScripted(in)

      out.received shouldEqual in.produced.grouped(param).take(out.size).toList
    }
  }

  "Intersperse" in check {
    testSetup
      .input[String]
      .output[String]
      .param(Gen.option(implicitly[Gen[String]]))
      .param(Gen.option(implicitly[Gen[String]]))
      .prop
      .from { (in, out, start, end) ⇒
        in.spout.intersperse(start.orNull, ",", end.orNull).drainTo(out.drain) shouldTerminate asScripted(in)

        val receivedStr = out.received.mkString
        val producedStr = in.produced.take(out.size).mkString(start getOrElse "", ",", end getOrElse "")
        if (out.terminalState == TestFixture.State.Completed) {
          receivedStr shouldEqual producedStr
        } else {
          producedStr should startWith(receivedStr)
        }
      }
  }

  "Limit" in check {
    testSetup.input[Int].output[Int].param(Gen.chooseNum(0, 5)).prop.from { (in, out, param) ⇒
      def pipeline =
        in.spout.limit(param.toLong).drainTo(out.drain)

      if (scriptedElementCount(in, out) <= param) {
        pipeline shouldTerminate asScripted(in)
        out.received shouldEqual in.produced.take(out.scriptedSize)
      } else {
        pipeline shouldTerminate withErrorLike { case StreamLimitExceeded(`param`, _) ⇒ }
      }
    }
  }

  "Map" in check {
    testSetup.input[Int].output[String].prop.from { (in, out) ⇒
      in.spout.map(_.toString).drainTo(out.drain) shouldTerminate asScripted(in)

      out.received shouldEqual in.produced.take(out.scriptedSize).map(_.toString)
    }
  }

  "Reduce" in check {
    testSetup.input[Int].output[Int].prop.from { (in, out) ⇒
      import TestFixture.State._

      in.spout.reduce(_ + _).drainTo(out.drain) shouldTerminate {
        case Cancelled ⇒ // input can be in any state
        case Completed ⇒
          if (in.terminalState == Error(TestError)) sys.error(s"Input error didn't propagate to stream output")
          else in.scriptedSize should not be (0)
        case Error(_: NoSuchElementException) ⇒ in.scriptedSize shouldEqual 0
        case x @ Error(e)                     ⇒ in.terminalState shouldEqual x
      }

      if (out.terminalState == TestFixture.State.Completed)
        out.received shouldEqual in.produced.sum :: Nil
    }
  }

  "Scan" in check {
    testSetup.input[Int].output[Double].prop.from { (in, out) ⇒
      in.spout.scan(4.2)(_ + _).drainTo(out.drain) shouldTerminate asScripted(in)

      val expected = in.produced.scanLeft(4.2)(_ + _).take(out.scriptedSize)
      if (in.scriptedError.isEmpty) out.received shouldEqual expected
      else out.received shouldEqual expected.take(out.received.size)
    }
  }

  "Take" in check {
    testSetup.input[Int].output[Int].param(Gen.chooseNum(0, 10)).prop.from { (in, out, param) ⇒
      import TestFixture.State._

      in.spout.take(param.toLong).drainTo(out.drain) shouldTerminate {
        case Cancelled | Completed ⇒ // any input state could end up here
        case error                 ⇒ in.terminalState shouldBe error
      }

      out.received shouldEqual in.produced.take(math.min(param, out.scriptedSize))
    }
  }

  "TakeLast" in check {
    testSetup.input[Int].output[Int].param(Gen.chooseNum(0, 10)).prop.from { (in, out, param) ⇒
      import TestFixture.State._

      in.spout.takeLast(param).drainTo(out.drain) shouldTerminate {
        case Cancelled | Completed ⇒ // any input state could end up here
        case error                 ⇒ in.terminalState shouldBe error
      }

      out.received shouldEqual in.produced.takeRight(param).take(out.size)
    }
  }

  "TakeWhile" in check {
    testSetup.input[Int].output[Int].param(Gen.chooseNum(0, 500)).prop.from { (in, out, param) ⇒
      import TestFixture.State._

      in.spout.takeWhile(_ < param).drainTo(out.drain) shouldTerminate {
        case Cancelled | Completed ⇒ // any input state could end up here
        case error                 ⇒ in.terminalState shouldBe error
      }

      out.received shouldEqual in.produced.takeWhile(_ < param).take(out.scriptedSize)
    }
  }
}
