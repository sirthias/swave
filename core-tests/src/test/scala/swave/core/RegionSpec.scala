/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import org.scalatest.{FreeSpec, Matchers}
import swave.core.graph.GlyphSet
import swave.core.macros._

import scala.concurrent.Promise
import scala.io.Source
import scala.util.control.NonFatal

// format: OFF
class RegionSpec extends FreeSpec with Matchers {

  implicit val env = StreamEnv()

  "Example 1" tests {
    Spout.ints(0)
      .map(_.toString)
      .to(Drain.head)
  }

  "Example 2" tests {
    Spout.ints(0)
      .take(10)
      .asyncBoundary()
      .map(_.toString)
      .to(Drain.head)
  }

  "Example 3" tests {
    Spout(1, 2, 3)
      .fanOutBroadcast()
        .sub.map(_.toString).end
        .sub.asyncBoundary().end
      .fanInConcat()
      .to(Drain.head)
  }

  "Example 4" tests {
    def upperChars(s: String): Spout[Char] =
      Spout(s.iterator).map(_.toUpper)

    def drain(promise: Promise[String]): Drain[Char, Unit] =
      Drain.mkString(limit = 100)
        .captureResult(promise)
        .async("bar")

    val result2 = Promise[String]()
    upperChars("Hello")
      .asyncBoundary("foo")
      .fanOutBroadcast()
        .sub.drop(2).concat(upperChars("-Friend-").asyncBoundary()).end
        .sub.take(2).asyncBoundary().multiply(2).end
      .fanInConcat()
      .tee(Pipe[Char].asyncBoundary().deduplicate.to(drain(result2)))
      .map(_.toLower)
      .to(Drain.mkString(100))
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  val examples: Map[String, String] =
    Source.fromInputStream(getClass.getResourceAsStream("/RegionSpec.examples.txt"))
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
        val superRegions = Graph.superRegions(pipeNet.seal().regions)
        val rendering =
          superRegions.map { sr =>
            Graph.from(sr.head.entryPoint, Graph.ExpandModules.All)
              .withGlyphSet(GlyphSet.`2x2 ASCII`)
              .withStageFormat((s, _) => s"${s.kind.name}: ${sr.indexOf(s.region)}")
              .render()
          }.mkString("\n***\n")
        try rendering shouldEqual expectedRendering
        catch {
          case NonFatal(e) ⇒
            println(rendering)
            throw e
        }
      }
  }
}
