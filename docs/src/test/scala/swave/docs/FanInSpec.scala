/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import org.scalatest.{FreeSpec, Matchers}

class FanInSpec extends FreeSpec with Matchers {

  "the examples in the `fan-in` chapter should work as expected" - {

    "example1" in {
      //#example1
      import scala.concurrent.Future
      import swave.core._

      implicit val env = StreamEnv()

      def foo = Spout(1, 2, 3)
      def bar = Spout(10, 20, 30)
      def baz = Spout(100, 200, 300)

      val result: Future[List[Int]] =
        foo
          .attach(bar)
          .attach(baz)
        //.attach(...)
          .fanInConcat()
          .drainToList(limit = 10)

      result.value.get.get shouldEqual Seq(1, 2, 3, 10, 20, 30, 100, 200, 300)
      //#example1
    }

    "example2" in {
      //#example2
      import scala.concurrent.Future
      import swave.core._

      implicit val env = StreamEnv()

      def reds = Spout.ints(from = 0, step = 10)
      def greens = Spout.ints(from = 100, step = 20)
      def blues = Spout.ints(from = 200, step = 30)

      val result: Future[List[(Int, Int, Int)]] =
        greens              // open = greens
          .attach(blues)    // open = greens :: blues
          .attachLeft(reds) // open = reds :: greens :: blues
          .fanInToTuple // Spout[(Int, Int, Int)]
          .take(3)
          .drainToList(limit = 10)

      result.value.get.get shouldEqual List(
        (0, 100, 200),
        (10, 120, 230),
        (20, 140, 260)
      )
      //#example2
    }

    "example3" in {
      //#example3
      import scala.concurrent.Future
      import swave.core._

      implicit val env = StreamEnv()

      case class Person(id: Long, name: String, age: Int)

      def ids = Spout.longs(1L)
      def names = Spout("Alice", "Bob", "Charlie", "David")
      def ages = Spout(27, 21, 48, 36)

      val result: Future[List[Person]] =
        ids
          .attach(names)
          .attach(ages)
          .fanInToProduct[Person]
          .drainToList(limit = 10)

      result.value.get.get shouldEqual List(
        Person(1L, "Alice", 27),
        Person(2L, "Bob", 21),
        Person(3L, "Charlie", 48),
        Person(4L, "David", 36)
      )
      //#example3
    }
  }
}
