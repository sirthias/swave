/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import org.scalatest.{FreeSpec, Matchers}
import scala.concurrent.Future
import swave.core._

class BasicSpec extends FreeSpec with Matchers {

  implicit val env = StreamEnv()

  "the examples in the basic chapter should work as expected" in {

    //#foo
    val foo: Spout[Char] = Spout('f', 'o', 'o')
    //#foo

    //#upperFoo
    val upperFoo: Spout[Char] = foo.map(_.toUpper)
    //#upperFoo

    //#piping
    val piping: Piping[Future[Char]] = upperFoo.to(Drain.head)
    //#piping

    def `only compiled, not actually run`() = {
      //#reuse
      // the stream of all natural numbers as Strings
      def numberStrings = Spout.from(1).map(_.toString)

      // print the first 10
      numberStrings.take(10).foreach(println)

      // print the 42nd
      println(numberStrings.drop(41).drainToHead())

      // concatenate the first hundred
      val s: Future[String] =
        numberStrings.take(100).drainToMkString(", ")
      //#reuse
    }
  }
}
