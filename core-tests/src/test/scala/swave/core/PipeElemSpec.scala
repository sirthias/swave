/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.io.Source
import scala.util.control.NonFatal
import org.scalatest.{FreeSpec, Matchers}
import swave.core.macros._

class PipeElemSpec extends FreeSpec with Matchers {

  "Example 1" tests {
    Spout.repeat(42).to(Drain.head)
  }

  "Example 2" tests {
    Spout(1, 2, 3).map(_.toString).to(Drain.foreach(println))
  }

  "Example 3" tests {
    val c = Coupling[Int]
    Spout(1, 2, 3)
      .concat(c.out)
      .fanOutBroadcast(eagerCancel = false)
      .sub
      .first
      .buffer(1)
      .map(_ + 3)
      .to(c.in)
      .subContinue
      .to(Drain.head)
  }

  "Example 4" tests {
    val foo = Module.Forward.from2[Int, String] { (a, b) ⇒
      a.attachN(2, b.fanOutBroadcast())
    } named "foo"
    Spout
      .from(0)
      .duplicate
      .attach(Spout("x", "y", "z"))
      .fromFanInVia(foo)
      .fanInConcat
      .map(_.toString)
      .to(Drain.first(2))
  }

  "Example 5" tests {
    Spout(1, 2, 3).fanOutBroadcast().sub.end.sub.end.fanInConcat.to(Drain.head)
  }

  "Example 6" tests {
    Spout.from(0).deduplicate.zip(Spout(4, 5, 6)).to(Drain.head)
  }

  "Example 7" tests {
    Spout(1, 2, 3).tee(Drain.ignore.dropResult).to(Drain.head)
  }

  "Example 8" tests {
    Spout
      .from(0)
      .map(_ * 2)
      .via(Pipe.fromDrainAndSpout[Int, String](Drain.cancelling, Spout.empty))
      .filterNot(_.isEmpty)
      .to(Drain.head)
  }

  val examples: Map[String, String] =
    Source
      .fromInputStream(getClass.getResourceAsStream("/PipeElemSpec.examples.txt"))
      .getLines()
      .scanLeft(Left(Nil): Either[List[String], List[String]]) {
        case (Left(lines), "")   ⇒ Right(lines.reverse)
        case (Left(lines), line) ⇒ Left(line :: lines)
        case (Right(_), line)    ⇒ Left(line :: Nil)
      }
      .collect { case Right(renderString) ⇒ renderString }
      .toList
      .groupBy(_.head)
      .map {
        case (name, listOfRenderLines) ⇒
          requireArg(listOfRenderLines.size == 1, ", which means you have an example name duplication in examples.txt")
          name → listOfRenderLines.head.tail.mkString("\n")
      }

  implicit class Example(name: String) {
    def tests(pipeNet: ⇒ Piping[_]): Unit =
      name in {
        val expectedRendering =
          examples.getOrElse(name + ':', sys.error(s"Section for '$name' not found in examples.txt"))
        val s = Graph.render(pipeNet.pipeElem, showParams = true)
        try s shouldEqual expectedRendering
        catch {
          case NonFatal(e) ⇒
            println(s)
            throw e
        }
      }
  }
}
