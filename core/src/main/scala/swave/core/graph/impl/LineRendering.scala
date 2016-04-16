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

package swave.core.graph.impl

import java.lang.StringBuilder
import swave.core.graph.{ Digraph, GlyphSet }
import scala.collection.immutable.VectorBuilder
import Infrastructure._

private[graph] object LineRendering {

  def renderLines[V](nodes: Vector[Node], glyphSet: GlyphSet): Digraph.Rendering[V] = {
    var charBuf: Array[Char] = Array.emptyCharArray
    val lines = new VectorBuilder[String]
    val sb = new StringBuilder
    var maxLineLength = 0

    val vertexRenderings =
      for (node ← nodes) yield {
        val maxChars = node.glyphs.size * glyphSet.columns * 2
        if (charBuf.length < maxChars) charBuf = new Array[Char](maxChars)
        lines.clear()

        for (glyphRow ← 0 until glyphSet.rows) {
          val endIx = node.glyphs.foldLeft(0)((ix, glyph) ⇒ glyphSet.place(glyph, glyphRow, charBuf, ix))
          sb.append(charBuf, 0, endIx)
          // trim whitespace at line end
          while (sb.length > 0 && Character.isWhitespace(sb.charAt(sb.length - 1))) sb.setLength(sb.length - 1)
          // if (glyphRow == glyphSet.rows / 2) sb.append(' ').append(node.xRank.level)
          val line = sb.toString
          maxLineLength = math.max(maxLineLength, line.length)
          lines += line
          sb.setLength(0)
        }

        Digraph.VertexRendering(node.vertex.asInstanceOf[V], lines.result())
      }

    Digraph.Rendering(glyphSet, maxLineLength, vertexRenderings)
  }

}
