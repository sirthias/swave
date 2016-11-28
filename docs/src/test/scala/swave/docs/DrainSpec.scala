/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import org.scalatest.{FreeSpec, Matchers}

class DrainSpec extends FreeSpec with Matchers {

  "the examples in the `drains` chapter should work as expected" - {

    "examples" in {
      //#examples
      import scala.concurrent.Future
      import swave.core._

      implicit val env = StreamEnv()

      // a drain, which produces the sum of all `Int` elements it receives
      def sumDrain: Drain[Int, Future[Int]] =
        Drain.fold(0)(_ + _)

      Spout(1 to 100)  // Spout[Int]
        .to(sumDrain)  // StreamGraph[Int]
        .run()         // StreamRun[Future[Int]]
        .result        // Future[Int]
        .value         // Option[Try[Int]]
        .get           // Try[Int]
        .get shouldEqual 5050

      // same but shorter
      Spout(1 to 100)
        .drainTo(sumDrain) // shortcut for `.to(sumDrain).run()`
        .value.get.get shouldEqual 5050
      //#examples
    }
  }
}
