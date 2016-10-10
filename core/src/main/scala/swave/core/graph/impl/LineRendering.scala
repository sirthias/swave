/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.graph.impl

import java.lang.StringBuilder
import swave.core.graph.{Digraph, GlyphSet}
import scala.collection.immutable.VectorBuilder
import Infrastructure._

private[graph] object LineRendering {

  def renderLines[V](nodes: Vector[Node], glyphSet: GlyphSet): Digraph.Rendering[V] = {
    var charBuf: Array[Char] = Array.emptyCharArray
    val lines                = new VectorBuilder[String]
    val sb                   = new StringBuilder
    var maxLineLength        = 0

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
