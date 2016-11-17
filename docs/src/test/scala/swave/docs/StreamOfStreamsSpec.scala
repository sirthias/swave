/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import org.scalatest.{FreeSpec, Matchers}

class StreamOfStreamsSpec extends FreeSpec with Matchers {

  "the examples in the `streams-of-streams` chapter should work as expected" - {

    "takeEvery" in {
      //#takeEvery
      import swave.core._
      implicit val env = StreamEnv()

      // simple extension for `Spout[T]`, could also be a value class
      implicit class RichSpout[T](underlying: Spout[T]) {
        def takeEvery(n: Long): Spout[T] =
          underlying                  // Spout[T]
            .inject                   // Spout[Spout[T]]
            .map(_.drop(n-1).take(1)) // Spout[Spout[T]]
            .flattenConcat()          // Spout[T]
      }

      Spout.ints(from = 1)
        .takeEvery(10)
        .take(5)
        .drainToList(limit = 100)
        .value.get.get shouldEqual Seq(10, 20, 30, 40, 50)
      //#takeEvery
    }
  }
}
