/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

object Debugging extends App {
  //#example
  import swave.core._

  implicit val env = StreamEnv()

  Spout.ints(from = 0)
    .logEvent("A")
    .map(_ * 2)
    .take(5)
    .fold(0)(_ + _)
    .logEvent("B")
    .drainToBlackHole()
  //#example
}