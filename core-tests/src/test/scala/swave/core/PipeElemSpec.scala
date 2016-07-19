/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.io.Source
import scala.util.control.NonFatal
import org.scalatest.{ Matchers, FreeSpec }
import swave.core.macros._

class PipeElemSpec extends FreeSpec with Matchers {

  "Example 1" tests {
    Stream.repeat(42).to(Drain.head)
  }

  "Example 2" tests {
    Stream(1, 2, 3).map(_.toString).to(Drain.foreach(println))
  }

  "Example 3" tests {
    val c = Coupling[Int]
    Stream(1, 2, 3)
      .concat(c.out)
      .fanOutBroadcast(eagerCancel = false).sub.first.buffer(1).map(_ + 3).to(c.in)
      .subContinue
      .to(Drain.head)
  }

  "Example 4" tests {
    val foo = Module.Forward.from2[Int, String] { (a, b) ⇒
      a.attachN(2, b.fanOutBroadcast())
    } named "foo"
    Stream.from(0)
      .duplicate
      .attach(Stream("x", "y", "z"))
      .fromFanInVia(foo)
      .fanInConcat
      .map(_.toString)
      .to(Drain.first(2))
  }

  "Example 5" tests {
    Stream(1, 2, 3)
      .fanOutBroadcast()
      .sub.end
      .sub.end
      .fanInConcat
      .to(Drain.head)
  }

  "Example 6" tests {
    Stream.from(0)
      .deduplicate
      .zip(Stream(4, 5, 6))
      .to(Drain.head)
  }

  "Example 7" tests {
    Stream(1, 2, 3)
      .tee(Drain.ignore.dropResult)
      .to(Drain.head)
  }

  val examples: Map[String, String] =
    Source.fromInputStream(getClass.getResourceAsStream("/PipeElemSpec.examples.txt")).getLines()
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
        val expectedRendering = examples.getOrElse(name + ':', sys.error(s"Section for '$name' not found in examples.txt"))
        val s = GraphRendering(pipeNet.pipeElem, showParams = true)
        try s shouldEqual expectedRendering
        catch {
          case NonFatal(e) ⇒
            println(s)
            throw e
        }
      }
  }
}
