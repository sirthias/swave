/*
 * Copyright © 2016 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swave.core

import scala.io.Source
import scala.util.control.NonFatal
import org.scalatest.{ Matchers, FreeSpec }
import swave.core.util._

class PipeElemSpec extends FreeSpec with Matchers {

  "Example 1" tests {
    Stream(1, 2, 3).to(Drain.head)
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
    Stream(1, 2, 3)
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
    Stream(1, 2, 3)
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
        val s = PipeElem.render(pipeNet.pipeElem, showParams = true)
        try s shouldEqual expectedRendering
        catch {
          case NonFatal(e) ⇒
            println(s)
            throw e
        }
      }
  }
}
