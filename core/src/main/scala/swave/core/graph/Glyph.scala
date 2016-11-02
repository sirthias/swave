/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.graph

import scala.annotation.tailrec
import swave.core.macros._

private[graph] sealed trait GlyphMarker // phantom type

/**
  * A `Glyph` represents an atomic "rendering unit" (or "cell"), which all graph renderings are composed of.
  * By choosing a particular [[GlyphSet]] (or creating one yourself) you have a lot of control over how
  * a graph rendering will appear.
  */
object Glyph {
  private[graph] def apply(i: Int)      = i.asInstanceOf[Glyph]
  private[graph] def idOf(glyph: Glyph) = glyph.asInstanceOf[Int]

  final val GLYPH_COUNT = 25

  // format: OFF
  val SPACE          = Glyph(0)
  val ROOT_LEAF      = Glyph(1)
  val ROOT           = Glyph(2)
  val LEAF           = Glyph(3)
  val NODE           = Glyph(4)
  val NODE_CORNER_TL = Glyph(5)
  val NODE_CORNER_BL = Glyph(6)
  val NODE_TEE_L     = Glyph(7)
  val LINE_H         = Glyph(8)
  val LINE_V         = Glyph(9)
  val LINE_V_REV     = Glyph(10)
  val CORNER_TL      = Glyph(11)
  val CORNER_TL_REV  = Glyph(12)
  val CORNER_TR      = Glyph(13)
  val CORNER_TR_REV  = Glyph(14)
  val CORNER_BL      = Glyph(15)
  val CORNER_BL_REV  = Glyph(16)
  val CORNER_BR      = Glyph(17)
  val CORNER_BR_REV  = Glyph(18)
  val TEE_T          = Glyph(19)
  val TEE_B          = Glyph(20)
  val TEE_R          = Glyph(21)
  val TEE_DOUBLE     = Glyph(22)
  val CROSSING       = Glyph(23)
  val CROSSING_REV   = Glyph(24)

  def hasOpenTop(glyph: Glyph): Boolean = hasOpenSides(glyph, OpenSides.TOP)
  def hasOpenRight(glyph: Glyph): Boolean = hasOpenSides(glyph, OpenSides.RIGHT)
  def hasOpenBottom(glyph: Glyph): Boolean = hasOpenSides(glyph, OpenSides.BOTTOM)
  def hasOpenLeft(glyph: Glyph): Boolean = hasOpenSides(glyph, OpenSides.LEFT)

  def hasOpenSides(glyph: Glyph, sides: Int): Boolean = (OpenSides(glyph) & sides) == sides

  object OpenSides {
    final val TOP = 1
    final val RIGHT = 2
    final val BOTTOM = 4
    final val LEFT = 8

    def apply(glyph: Glyph): Int =
      glyph match {
        case SPACE | ROOT_LEAF         => 0
        case ROOT                      => BOTTOM
        case LEAF                      => TOP
        case NODE                      => TOP | BOTTOM
        case NODE_CORNER_TL            => RIGHT | BOTTOM
        case NODE_CORNER_BL            => RIGHT | TOP
        case NODE_TEE_L                => TOP | RIGHT | BOTTOM
        case LINE_H                    => LEFT | RIGHT
        case LINE_V | LINE_V_REV       => TOP | BOTTOM
        case CORNER_TL | CORNER_TL_REV => RIGHT | BOTTOM
        case CORNER_TR | CORNER_TR_REV => LEFT | BOTTOM
        case CORNER_BL | CORNER_BL_REV => RIGHT | TOP
        case CORNER_BR | CORNER_BR_REV => LEFT | TOP
        case TEE_T                     => LEFT | RIGHT | BOTTOM
        case TEE_B                     => LEFT | RIGHT | TOP
        case TEE_R                     => LEFT | TOP | BOTTOM
        case TEE_DOUBLE                => LEFT | RIGHT | TOP | BOTTOM
        case CROSSING | CROSSING_REV   => LEFT | RIGHT | TOP | BOTTOM
      }
  }

  // format: ON
}

/**
  * A [[GlyphSet]] is to a [[Glyph]] what a Font is to an ASCII (integer) value.
  * It defines, how exactly each existing [[Glyph]] instance is to be rendered.
  *
  * Each [[Glyph]] is rendered into a grid of actual characters whereby the grid size is fixed (for this [[GlyphSet]].
  *
  * You can construct your own Glyphsets via the `GlyphSet.apply` method.
  */
final class GlyphSet private (val rows: Int, val columns: Int, private[this] val codepoints: Array[Int]) {

  private[graph] def place(glyph: Glyph, row: Int, dest: Array[Char], destIx: Int): Int = {
    @tailrec def rec(col: Int, ix: Int, destIx: Int): Int =
      if (col < columns) rec(col + 1, ix + 1, destIx + Character.toChars(codepoints(ix), dest, destIx))
      else destIx
    rec(0, (Glyph.idOf(glyph) * rows + row) * columns, destIx)
  }
}

object GlyphSet {
  import Glyph._

  /**
    * Creates a new [[GlyphSet]] with the given per-Glyph-grid size from a function, which, for each [[Glyph]]
    * returns a [[String]] containing the Glyph's characters in one or more lines.
    *
    * All Strings returned by the function must be `rows * columns + (rows - 1) * lineSep.length` characters long.
    */
  def apply(rows: Int, columns: Int, lineSep: String = "")(f: Glyph ⇒ String): GlyphSet = {
    requireArg(rows > 0, "`rows` must be > 0")
    requireArg(columns > 0, "`columns` must be > 0")
    val codepoints = new Array[Int](GLYPH_COUNT * rows * columns)

    @tailrec def prepareGlyphs(codepointsIx: Int, glyph: Glyph, str: String, strIx: Int, row: Int, col: Int): Unit =
      if (col < columns) {
        val cp =
          try str.codePointAt(strIx)
          catch {
            case e: IndexOutOfBoundsException ⇒ throw new RuntimeException(s"string for glyph $glyph is too short", e)
          }
        codepoints(codepointsIx) = cp
        prepareGlyphs(codepointsIx + 1, glyph, str, strIx + Character.charCount(cp), row, col + 1)
      } else if (row < rows - 1) {
        requireArg(
          str.indexOf(lineSep, strIx) == strIx,
          s", expected line separator not found in row $row of glyph $glyph")
        prepareGlyphs(codepointsIx, glyph, str, strIx + lineSep.length, row + 1, col = 0)
      } else if (Glyph.idOf(glyph) < GLYPH_COUNT - 1) {
        val nextGlyph = Glyph(Glyph.idOf(glyph) + 1)
        prepareGlyphs(codepointsIx, nextGlyph, f(nextGlyph), strIx = 0, row = 0, col = 0)
      } else requireArg(strIx == str.length, s"string for glyph $glyph is too long")

    prepareGlyphs(codepointsIx = 0, glyph = SPACE, str = f(SPACE), strIx = 0, row = 0, col = 0)
    new GlyphSet(rows, columns, codepoints)
  }

  // format: OFF

  val `2x2 UTF`: GlyphSet = GlyphSet(2, 2) {
    case SPACE          =>
      "　　" +
      "　　"
    case ROOT_LEAF      =>
      "◉　" +
      "　　"
    case ROOT           =>
      "┳　" +
      "┃　"
    case LEAF           =>
      "┻　" +
      "　　"
    case NODE           =>
      "◉　" +
      "┃　"
    case NODE_CORNER_TL =>
      "◉━" +
      "┃　"
    case NODE_CORNER_BL =>
      "◉━" +
      "　　"
    case NODE_TEE_L     =>
      "◉━" +
      "┃　"
    case LINE_H         =>
      "━━" +
      "　　"
    case LINE_V         =>
      "┃　" +
      "┃　"
    case LINE_V_REV     =>
      "┇　" +
      "┇　"
    case CORNER_TL      =>
      "┏━" +
      "┃　"
    case CORNER_TL_REV  =>
      "┏┅" +
      "┇　"
    case CORNER_TR      =>
      "┓　" +
      "┃　"
    case CORNER_TR_REV  =>
      "┓　" +
      "┇　"
    case CORNER_BL      =>
      "┗━" +
        "　　"
    case CORNER_BL_REV  =>
      "┗┅" +
      "　　"
    case CORNER_BR | CORNER_BR_REV  =>
      "┛　" +
      "　　"
    case TEE_T          =>
      "┳━" +
      "┃　"
    case TEE_B          =>
      "┻　" +
      "　　"
    case TEE_R          =>
      "┫　" +
      "┃　"
    case TEE_DOUBLE     =>
      "╋━" +
      "┃　"
    case CROSSING       =>
      "┃━" +
      "┃　"
    case CROSSING_REV   =>
      "┇━" +
      "┇　"
  }

  val `2x2 ASCII`: GlyphSet = GlyphSet(2, 2) {
    case SPACE          =>
      "  " +
      "  "
    case ROOT_LEAF      =>
      "o " +
      "  "
    case ROOT           =>
      "= " +
      "| "
    case LEAF           =>
      "= " +
      "  "
    case NODE           =>
      "o " +
      "| "
    case NODE_CORNER_TL =>
      "o-" +
      "| "
    case NODE_CORNER_BL =>
      "o-" +
      "  "
    case NODE_TEE_L     =>
      "o-" +
      "| "
    case LINE_H         =>
      "--" +
      "  "
    case LINE_V         =>
      "| " +
      "| "
    case LINE_V_REV     =>
      "^ " +
      "^ "
    case CORNER_TL      =>
      "+-" +
      "| "
    case CORNER_TL_REV  =>
      ">>" +
      "^ "
    case CORNER_TR      =>
      "+ " +
      "| "
    case CORNER_TR_REV  =>
      "+ " +
      "^ "
    case CORNER_BL      =>
      "+-" +
      "  "
    case CORNER_BL_REV  =>
      "^<" +
      "  "
    case CORNER_BR | CORNER_BR_REV =>
      "+ " +
      "  "
    case TEE_T          =>
      "+-" +
      "| "
    case TEE_B          =>
      "+-" +
      "  "
    case TEE_R          =>
      "+ " +
      "| "
    case TEE_DOUBLE     =>
      "+-" +
      "| "
    case CROSSING       =>
      "|-" +
      "| "
    case CROSSING_REV   =>
      "|-" +
      "| "
  }

  val `3x3 ASCII`: GlyphSet = GlyphSet(3, 3) {
    case SPACE          =>
      "   " +
      "   " +
      "   "
    case ROOT_LEAF      =>
      "   " +
      " o " +
      "   "
    case ROOT           =>
      "   " +
      " o " +
      " | "
    case LEAF           =>
      " | " +
      " o " +
      "   "
    case NODE           =>
      " | " +
      " o " +
      " | "
    case NODE_CORNER_TL =>
      "   " +
      " o-" +
      " | "
    case NODE_CORNER_BL =>
      " | " +
      " o-" +
      "   "
    case NODE_TEE_L     =>
      " | " +
      " o-" +
      " | "
    case LINE_H         =>
      "   " +
      "---" +
      "   "
    case LINE_V         =>
      " | " +
      " | " +
      " | "
    case LINE_V_REV     =>
      " ^ " +
      " ^ " +
      " ^ "
    case CORNER_TL      =>
      "   " +
      " +-" +
      " | "
    case CORNER_TL_REV  =>
      "   " +
      " >>" +
      " ^ "
    case CORNER_TR      =>
      "   " +
      "-+ " +
      " | "
    case CORNER_TR_REV  =>
      "   " +
      "<< " +
      " ^ "
    case CORNER_BL      =>
      " | " +
      " +-" +
      "   "
    case CORNER_BL_REV  =>
      " ^ " +
      " ^<" +
      "   "
    case CORNER_BR      =>
      " | " +
      "-+ " +
      "   "
    case CORNER_BR_REV  =>
      " ^ " +
      ">^ " +
      "   "
    case TEE_T          =>
      "   " +
      "-+-" +
      " | "
    case TEE_B          =>
      " | " +
      "-+-" +
      "   "
    case TEE_R          =>
      " | " +
      "-+ " +
      " | "
    case TEE_DOUBLE     =>
      " | " +
      "-+-" +
      " | "
    case CROSSING       =>
      " | " +
      "-|-" +
      " | "
    case CROSSING_REV   =>
      " ^ " +
      "-^-" +
      " ^ "
  }
}
