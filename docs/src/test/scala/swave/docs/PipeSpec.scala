/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import org.scalatest.{FreeSpec, Matchers}
import scala.concurrent.Future
import swave.core._

class PipeSpec extends FreeSpec with Matchers {

  implicit val env = StreamEnv()

  "the examples in the `Pipes` chapter should work as expected" in {

    //#simplePipe
    def simplePipe: Pipe[Int, String] =
      Pipe[Int].filter(_ % 2 == 0).map(_.toString)
    //#simplePipe

    //#slice
    def slice[A](startIndex: Long, length: Long): Pipe[A, A] =
      Pipe[A] drop startIndex take length named "slice"
    //#slice

    //#spout-via
    def someInts: Spout[Int] =
      Spout.ints(from = 0).via(slice(10, 42))

    someInts.drainToList(limit = 100)
      .value.get.get shouldEqual (10 to 51)
    //#spout-via

    //#pipe-to-drain
    def intDrain: Drain[Int, Future[String]] =
      Pipe[Int].map(_ * 2).to(Drain.mkString(limit = 100, sep = ", "))

    Spout.ints(from = 0)
      .take(5)
      .drainTo(intDrain)
      .value.get.get shouldEqual "0, 2, 4, 6, 8"
    //#pipe-to-drain

    //#pipe-to-pipe
    def shareOfZeroFigures: Pipe[Int, Double] =
      Pipe[Int]
        .map(_.toString)
        .map { s =>
          s.foldLeft(0) {
            case (zeroes, '0') => zeroes + 1
            case (zeroes, _) => zeroes
          } / s.length.toDouble
        }

    def stringify: Pipe[Double, String] =
      Pipe[Double].map("%.2f" format _)

    def compound: Pipe[Int, String] =
      shareOfZeroFigures via stringify

    Spout.ints(from = 98)
      .take(5)
      .via(compound)
      .drainToMkString(limit = 100, sep = ", ")
      .value.get.get shouldEqual "0.00, 0.00, 0.67, 0.33, 0.33"
    //#pipe-to-pipe
  }
}
