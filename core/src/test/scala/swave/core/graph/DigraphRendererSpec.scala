/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.graph

import scala.util.control.NonFatal
import scala.io.Source
import org.scalatest.{FreeSpec, Matchers}
import swave.core.macros._

class DigraphRendererSpec extends FreeSpec with Matchers {
  import GraphBuilding._

  val glyphSet = GlyphSet.`3x3 ASCII`

  "Example 0" tests {
    input("A").toDigraph
  }

  "Example 1" tests {
    input("A").to("B")
  }

  "Example 2" tests {
    input("A").next("B").next("C").to("D")
  }

  "Example 3a" tests {
    input("A").attach(input("B")).fanIn("C").to("D")
  }

  "Example 3b" tests {
    input("A").attach(input("B")).fanIn("C").toDigraph
  }

  "Example 4a" tests {
    input("A").fanOut("B").sub.to("C").subContinue.to("D")
  }

  "Example 4b" tests {
    fanOut("A").sub.to("B").subContinue.to("C")
  }

  "Example 5" tests {
    input("A").attach(input("B")).attach(input("C")).fanIn("D").to("E")
  }

  "Example 6" tests {
    input("A").fanOut("B").sub.to("C").sub.to("D").subContinue.to("E")
  }

  "Example 7" tests {
    input("A")
      .fanOut("B")
      .sub
      .to("C")
      .sub
      .next("D")
      .attach(input("E").next("F"))
      .fanIn("G")
      .end
      .sub
      .next("H")
      .to("I")
      .continue
      .to("J")
  }

  "Example 8" tests {
    val branch1 =
      input("C")
        .fanOut("D")
        .sub
        .next("E")
        .next("F")
        .to("G")
        .sub
        .next("H")
        .end
        .sub
        .next("I")
        .next("J")
        .next("K")
        .to("L")
        .continue

    val branch2 =
      input("M").next("N").fanOut("O").sub.next("P").end.sub.next("Q").next("R").next("S").to("T").continue

    input("A").next("B").attach(branch1).attach(branch2).fanIn("U").toDigraph
  }

  "Example 9" tests {
    fanOut("A").sub.to("B").sub.fanOut("C").subDrains("D", "E").subContinue.to("F").subContinue.to("G")
  }

  "Example 10" tests {
    fanOut("A").sub
      .next("B")
      .next("C")
      .to("D")
      .sub
      .fanOut("E")
      .subDrains("F")
      .subContinue
      .to("G")
      .sub
      .next("H")
      .next("I")
      .next("J")
      .to("K")
      .subContinue
      .to("L")
  }

  "Example 11a" tests {
    fanOut("A").sub.end.sub.end.fanIn("B").toDigraph
  }

  "Example 11b" tests {
    fanOut("A").sub.next("B").end.sub.end.fanIn("C").toDigraph
  }

  "Example 11c" tests {
    fanOut("A").sub.end.sub.next("B").end.fanIn("C").toDigraph
  }

  "Example 11d" tests {
    fanOut("A").sub.next("B").next("C").end.sub.next("D").end.fanIn("E").toDigraph
  }

  "Example 12a" tests {
    input("A").attach(input("B")).attach(input("C")).fanInAndOut("D").subDrains("E", "F").subContinue.to("G")
  }

  "Example 12b" tests {
    input("A").attach(input("B")).attach(input("C")).fanInAndOut("D").subDrains("E").subContinue.to("F")
  }

  "Example 12c" tests {
    input("A").attach(input("B")).fanInAndOut("C").subDrains("D", "E").subContinue.to("F")
  }

  "Example 13" tests {
    val cp = coupling("C")
    input("A").attach(cp.input).fanInAndOut("B").sub.end.sub.to(cp).continue.to("D")
  }

  "Example 14" tests {
    val c = coupling("C")
    fanOut("A").sub.end.sub
      .to(c)
      .continue
      .fanOut("B")
      .sub
      .attach(c.input)
      .fanIn("D")
      .end
      .sub
      .attach(c.input)
      .fanIn("E")
      .end
      .fanIn("F")
      .toDigraph
  }

  "Example 15" tests {
    val e = coupling("E")
    fanOut("A").sub
      .attach(fanOut("B").sub.end.sub.to(e).continue)
      .fanIn("C")
      .end
      .attach(e.input)
      .sub
      .attachLeft(fanOut("D").sub.to(e).subContinue)
      .fanIn("F")
      .end
      .fanIn("G")
      .to("H")
  }

  val examples: Map[String, String] =
    Source
      .fromInputStream(getClass.getResourceAsStream("/DigraphRendererSpec.examples.txt"))
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
    def tests(graph: ⇒ Digraph[Dsl.Node]): Unit =
      name in {
        val expectedRendering =
          examples.getOrElse(name + ':', sys.error(s"Section for '$name' not found in examples.txt"))
        val s = graph.render(glyphSet).format(_.value)
        try s shouldEqual expectedRendering
        catch {
          case NonFatal(e) ⇒
            println(s)
            throw e
        }
      }
  }
}
