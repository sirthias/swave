/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import org.scalatest.{FreeSpec, Matchers}

class FibonacciSpec extends FreeSpec with Matchers {

  "the examples in the `fibonacci` chapter should work as expected" - {

    "unfold" in {
      //#unfold
      import swave.core._
      implicit val env = StreamEnv()

      // the "infinite" stream of all Fibonacci numbers
      def fibonacciNumbers: Spout[Int] =
        Spout.unfold(0 -> 1) { case (a, b) =>
          Spout.Unfolding.Emit(elem = a, next = b -> (a + b))
        }

      fibonacciNumbers
        .take(8)
        .drainToList(limit = 100)
        .value.get.get shouldEqual List(0, 1, 1, 2, 3, 5, 8, 13)
      //#unfold
    }

    "cycle" in {
      //#cycle
      import swave.core._
      implicit val env = StreamEnv()

      val c = Coupling[Int]

      def fibonacciNumbers =
        Spout(0, 1)
          .concat(c.out)
          .fanOutBroadcast(eagerCancel = true)
            .sub.buffer(2, Buffer.RequestStrategy.Always).sliding(2).map(_.sum).to(c.in)
            .subContinue

      fibonacciNumbers
        .take(8)
        .drainToList(limit = 100)
        .value.get.get shouldEqual List(0, 1, 1, 2, 3, 5, 8, 13)
      //#cycle
    }
  }
}
