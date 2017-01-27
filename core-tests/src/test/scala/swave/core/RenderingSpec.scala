/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import scala.io.Source
import scala.concurrent.Promise
import scala.util.control.NonFatal
import org.scalatest.{FreeSpec, Matchers}
import swave.core.macros._

// format: OFF
class RenderingSpec extends FreeSpec with Matchers {

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
        .sub.first.buffer(1).map(_ + 3).to(c.in)
        .subContinue.to(Drain.head)
  }

  "Example 4" tests {
    val foo = Module.Forward.from2[Int, String] { (a, b) ⇒
      a.attachN(2, b.fanOutBroadcast())
    } named "foo"
    Spout.ints(0)
      .duplicate
      .attach(Spout("x", "y", "z"))
      .fromFanInVia(foo)
      .fanInConcat()
      .map(_.toString)
      .to(Drain.first(2))
  }

  "Example 5" tests {
    Spout(1, 2, 3)
      .fanOutBroadcast()
        .sub.end
        .sub.end
      .fanInConcat()
      .to(Drain.head)
  }

  "Example 6" tests {
    Spout.ints(0)
      .deduplicate
      .zip(Spout(4, 5, 6))
      .to(Drain.head)
  }

  "Example 7" tests {
    Spout(1, 2, 3)
      .tee(Drain.ignore.dropResult)
      .to(Drain.head)
  }

  "Example 8" tests {
    Spout.ints(0)
      .map(_ * 2)
      .via(Pipe.fromDrainAndSpout[Int, String](Drain.cancelling, Spout.empty))
      .filterNot(_.isEmpty)
      .to(Drain.head)
  }

  "Example 9" tests {
    def upperChars(s: String): Spout[Char] =
      Spout(s.iterator).map(_.toUpper)

    def drain(promise: Promise[String]): Drain[Char, Unit] =
      Drain.mkString(limit = 100)
        .captureResult(promise)
        .async("bar")

    val result2 = Promise[String]()
    upperChars("Hello")
      .logSignal("A")
      .asyncBoundary("foo")
      .logSignal("B")
      .fanOutBroadcast()
      .sub.drop(2).logSignal("C").concat(upperChars("-Friend-").asyncBoundary()).end
        .sub.take(2).logSignal("D").asyncBoundary().multiply(2).end
      .fanInConcat()
      .logSignal("E")
      .tee(Pipe[Char].asyncBoundary().logSignal("F").deduplicate.to(drain(result2)))
      .map(_.toLower)
      .logSignal("G")
      .to(Drain.mkString(100))
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  val examples: Map[String, String] =
    Source.fromInputStream(getClass.getResourceAsStream("/RenderingSpec.examples.txt"))
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
    def tests(pipeNet: ⇒ StreamGraph[_]): Unit =
      name in {
        val expectedRendering =
          examples.getOrElse(name + ':', sys.error(s"Section for '$name' not found in examples.txt"))
        val rendering = Graph.from(pipeNet.stage)
          .withStageFormat((s, _) => s.kind.name)
          .render()
        try rendering shouldEqual expectedRendering
        catch {
          case NonFatal(e) ⇒
            println(rendering)
            throw e
        }
      }
  }
}
