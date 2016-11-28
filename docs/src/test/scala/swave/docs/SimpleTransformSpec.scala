/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import org.scalatest.{FreeSpec, Matchers}

class SimpleTransformSpec extends FreeSpec with Matchers {

  "the examples in the `simple transformations` chapter should work as expected" - {

    "example" in {
      //#example
      import scala.concurrent.Future
      import swave.core._

      implicit val env = StreamEnv()

      val result: Future[String] =
      Spout(1, 2, 3, 4, 5) // Spout[Int]
        .map(_ * 2)        // Spout[Int]
        .filter(_ > 5)     // Spout[Int]
        .reduce(_ + _)     // Spout[Int]
        .map(_.toString)   // Spout[String]
        .drainToHead()     // Future[String]

      result.value.get.get shouldEqual "24"
      //#example
    }
  }
}
