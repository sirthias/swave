/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import org.scalatest.{FreeSpec, Matchers}

import scala.concurrent.Promise

class FanOutSpec extends FreeSpec with Matchers {

  "the examples in the `fan-out` chapter should work as expected" - {

    "basic-example" in {
      //#basic-example
      import swave.core._
      implicit val env = StreamEnv()

      val promise1 = Promise[Seq[String]]()
      val drain1 =
        Drain.seq(limit = 10).captureResult(promise1)

      val promise2 = Promise[Seq[Int]]()
      val drain2 =
        Drain.seq(limit = 10).captureResult(promise2)

      Spout.ints(from = 0)
        .fanOutBroadcast(eagerCancel = true)
          .sub.filter(_ > 45).map(_.toString).to(drain1)
          .sub.map(_.toString).end
          .sub.slice(42, 7).to(drain2) // terminates the stream by cancelling after the 48th element
        .continue
        .drop(10)
        .drainToMkString(limit = 100)
        .value.get.get shouldEqual (10 to 48).mkString

      promise1.future.value.get.get shouldEqual (46 to 48).map(_.toString)
      promise2.future.value.get.get shouldEqual (42 to 48)
      //#basic-example
    }

    "diamond" in {
      //#diamond
      import swave.core._
      implicit val env = StreamEnv()

      Spout(1, 2, 3)
        .fanOutBroadcast()
          .sub.map("A" + _).end
          .sub.map("B" + _).end
        .fanInToTuple // Spout[(String, String)]
        .drainToMkString(limit = 10, sep = ";")
        .value.get.get shouldEqual "(A1,B1);(A2,B2);(A3,B3)"
      //#diamond
    }

    "mixed" in {
      //#mixed
      import swave.core._
      implicit val env = StreamEnv()

      Spout(1, 2, 3)
        .fanOutBroadcast()
          .sub.map(_.toString).end
          .attach(Spout.longs(from = 10))
          .sub.to(Drain.ignore.dropResult) // just for fun
          .sub.end
          .attachLeft(Spout.doubles(from = 0, step = 0.5))
        .fanInToTuple // Spout[(Double, String, Long, Int)]
        .drainToList(limit = 10)
        .value.get.get shouldEqual Seq(
        (0.0, "1", 10, 1),
        (0.5, "2", 11, 2),
        (1.0, "3", 12, 3))
      //#mixed
    }

    "teee" in {
      //#teee
      import swave.core._
      implicit val env = StreamEnv()

      // simple extension for `Spout[T]`, could also be a value class
      implicit class RichSpout[T](underlying: Spout[T]) {
        def teee(drain: Drain[T, Unit]): Spout[T] =
          underlying
            .fanOutBroadcast()
              .sub.to(drain)
              .subContinue // short for: .sub.end.continue
      }

      val promise = Promise[Seq[Int]]()

      Spout(1, 2, 3)
        .teee(Drain.seq(limit = 10).captureResult(promise))
        .drainToList(limit = 10)
        .value.get.get shouldEqual Seq(1, 2, 3)

      promise.future.value.get.get shouldEqual Seq(1, 2, 3)
      //#teee
    }
  }
}
