/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import org.scalatest.{FreeSpec, Matchers}

import scala.collection.immutable
import scala.concurrent.Future

//#core-import
import swave.core._
//#core-import

class QuickStartSpec extends FreeSpec with Matchers {

  //#env
  implicit val env = StreamEnv()
  //#env

  "the examples in the `quick-start` chapter should work as expected" in {

    //#spout
    def tenInts: Spout[Int] = Spout(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    //#spout

    //#spout-simple-ops
    def threeInts = tenInts
      .map(_ * 2)
      .filter(_ > 6)
      .take(3)
    //#spout-simple-ops

    threeInts.drainToList(10).value.get.get shouldEqual List(8, 10, 12)

    //#ten-spouts
    def a = Spout(1 to 100)

    def b = Spout(1, 2, 3 to 100: _*)

    def c = Spout(100 to 1 by -1).map(101 - _)

    def d = Spout.ints(from = 1, step = 1).take(100)

    def e = Spout.iterate(1)(_ + 1).takeWhile(_ <= 100)

    def f = Spout.repeat(1).take(99).scan(1)(_ + _)

    def g = Spout(1 to 50) concat Spout(51 to 100)

    def h = Spout(List.tabulate(10, 10)(_ * 10 + _))
      .flattenConcat()
      .map(_ + 1)

    def i = {
      var i = 0
      Spout.continually { i += 1; i }.take(100)
    }

    def j = Spout.unfold(1) { i =>
      if (i < 100) Spout.Unfolding.Emit(i, next = i + 1)
      else Spout.Unfolding.EmitFinal(i)
    }
    //#ten-spouts

    List(a, b, c, d, e, f, g, h, i, j) foreach {
      _.drainToList(1000).value.get.get shouldEqual (1 to 100)
    }

    //#seq-drain
    def drain[T]: Drain[T, Future[immutable.Seq[T]]] = Drain.seq[T](limit = 1000)
    //#seq-drain

    //#more-drains
    def drain1[T]: Drain[T, Future[T]] = Drain.head
    def drain2[T]: Drain[T, Future[Unit]] = Drain.foreach(println)
    //#more-drains

    //#streamGraph
    def streamGraph: StreamGraph[Future[Unit]] =
      Spout(1 to 100).to(Drain.foreach(println))
    //#streamGraph

    def `only compiled, not actually run`() = {
      //#run
      val result: Future[Unit] =
        Spout(1 to 100)
          .to(Drain.foreach(println))
          .run().get.result
      //#run

      //#shortcuts
      def spout = Spout(1 to 100)

      val result1: Future[Unit] = spout.foreach(println)

      val result2: Future[Int] = spout.drainToHead()

      val result3: Future[List[Int]] = spout.drainToList(limit = 1000)
      //#shortcuts
    }

    //#env-shutdown
    env.shutdown()
    //#env-shutdown
  }
}
