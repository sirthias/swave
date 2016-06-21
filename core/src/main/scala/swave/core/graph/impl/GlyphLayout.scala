/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.graph.impl

import scala.annotation.tailrec
import scala.collection.mutable
import swave.core.graph.{ Digraph, Glyph }
import swave.core.macros._
import swave.core.util._
import Digraph.EdgeAttributes.Reversed
import Infrastructure._

private[graph] object GlyphLayout {

  def layoutRows(nodes: Vector[Node], edgeAttrs: EdgeAttrs): Vector[Node] = {
    import Glyph._

    class Lane(var target: Node, val rank: XRank, val isReversedEdge: Boolean)
    val lanes = new mutable.ArrayBuffer[Lane]

    // STEP 1: assign glyphs
    for (current ← nodes) {
      import current._

      def groupLanes(node: Node): List[Lane] =
        lanes.withFilter(ln ⇒ ln.target != current && ln.rank.group == node.xRank.group)
          .map(identityFunc)(collection.breakOut)

      def emitLine(mainGlyphs: Glyph*): Unit =
        for (lane ← lanes) {
          if (lane.target == current) {
            glyphs ++= mainGlyphs
          } else glyphs += (if (lane.isReversedEdge) LINE_V_REV else LINE_V)
        }

      current match {
        case Root(Seq(target)) ⇒
          val grpLanes = groupLanes(current)
          val bestLane =
            if (grpLanes.nonEmpty) {
              grpLanes.indexWhere(_.rank.level > current.xRank.level) match {
                case -1 ⇒ lanes.indexOf(grpLanes.last) + 1 // we only have a minimum lane, so pick it
                case ix ⇒ lanes.indexOf(grpLanes(ix)) // we have a maximum lane, so pick it
              }
            } else lanes.size // we have no constraint on where to put the node
          lanes.insert(bestLane, new Lane(current, current.xRank, isReversedEdge = false))
          emitLine(ROOT)
          lanes(bestLane).target = target

        case Leaf(Seq(_)) ⇒
          emitLine(LEAF)
          lanes.remove(lanes.indexWhere(_.target == current))

        case InOut(origin, target) ⇒
          emitLine(NODE)
          lanes(lanes.indexWhere(_.target == current)).target = target

        case Root(Nil) ⇒ glyphs += ROOT_LEAF

        case _ ⇒
          def laneMatch(node: Node, lane: Int, preferHere: Boolean): Boolean =
            lane == lanes.size || {
              val grpLanes = groupLanes(node)
              if (grpLanes.nonEmpty) {
                grpLanes.indexWhere(_.rank.level > node.xRank.level) match {
                  case -1 ⇒ // all open lanes have a level < the node level, so we have no max lane
                    val minLane = lanes.indexOf(grpLanes.last) + 1
                    lane >= minLane && preferHere
                  case 0 ⇒ // all open lanes have a level > the node level, so we have no min lane
                    val maxLane = lanes.indexOf(grpLanes.head)
                    lane == maxLane || preferHere
                  case ix ⇒ // we have a min as well as a max lane
                    val minLane = lanes.indexOf(grpLanes(ix - 1)) + 1
                    val maxLane = lanes.indexOf(grpLanes(ix))
                    lane >= minLane && (lane == maxLane || preferHere)
                }
              } else preferHere // we have no constraint on where to put the node
            }

          @tailrec def rec(lane: Int, remainingPreds: Int, succIx: Int): Unit = {
            def hadIns = remainingPreds < preds.size
            def lastIn = remainingPreds == 1
            def insCompleted = remainingPreds == 0
            def hadOuts = succIx > 0
            def lastOut = succIx == succs.size - 1
            def outsCompleted = succIx == succs.size
            def laneInstance = { val s = succs(succIx); new Lane(s, s.xRank, edgeAttrs.has(current → s, Reversed)) }
            val hasIn = lane < lanes.size && lanes(lane).target == current
            val hasOut = succIx < succs.size && laneMatch(succs(succIx), lane, hasIn || hadIns)

            if (hasIn && hasOut) {
              lanes(lane) = laneInstance
              glyphs += (if (!hadIns && !hadOuts) NODE_TEE_L else if (lastIn && lastOut) TEE_R else TEE_DOUBLE)
              rec(lane + 1, remainingPreds - 1, succIx + 1)
            } else if (hasIn && !hasOut) {
              lanes.remove(lane)
              glyphs += (if (lastIn && outsCompleted) CORNER_BR else if (!hadIns && !hadOuts) NODE_CORNER_BL else TEE_B)
              rec(lane, remainingPreds - 1, succIx)
            } else if (!hasIn && hasOut) {
              lanes.insert(lane, laneInstance)
              glyphs += (if (lastOut && insCompleted) CORNER_TR else if (!hadIns && !hadOuts) NODE_CORNER_TL else TEE_T)
              rec(lane + 1, remainingPreds, succIx + 1)
            } else if (lane < lanes.size) {
              def lineV = if (lanes(lane).isReversedEdge) LINE_V_REV else LINE_V
              def crossing = if (lanes(lane).isReversedEdge) CROSSING_REV else CROSSING
              glyphs += (if (!hadIns && !hadOuts || insCompleted && outsCompleted) lineV else crossing)
              rec(lane + 1, remainingPreds, succIx)
            } else requireState(insCompleted && outsCompleted)
          }

          rec(0, preds.size, 0)
      }
    }

    // STEP 2: remove lines for synthetic nodes
    val rows = nodes.filterNot(_.isHidden)

    // STEP 3: connect shifts downwards
    def glyphAt(seq: Seq[Glyph], ix: Int): Glyph = if (ix < seq.size) seq(ix) else SPACE

    for (currentIx ← rows.indices drop 1) {
      val prevLineGlyphs = rows(currentIx - 1).glyphs
      val currLineGlyphs = rows(currentIx).glyphs

      @tailrec def rec(ix: Int): Unit = {
        val prevLineGlyph = glyphAt(prevLineGlyphs, ix)
        val currLineGlyph = glyphAt(currLineGlyphs, ix)
        if (hasOpenTop(currLineGlyph) && !hasOpenBottom(prevLineGlyph)) {
          val j = Range(ix + 1, prevLineGlyphs.size).indexWhere(i ⇒ hasOpenBottom(prevLineGlyphs(i)))
          def connect(leftGlyph: Glyph, cornerBR: Glyph): Unit = {
            currLineGlyphs(ix) = leftGlyph
            currLineGlyphs.insert(ix + 1, cornerBR)
            j.times(currLineGlyphs.insert(ix + 1, LINE_H))
          }
          currLineGlyph match {
            case NODE                      ⇒ connect(NODE_CORNER_TL, CORNER_BR)
            case LINE_V | CROSSING         ⇒ connect(CORNER_TL, CORNER_BR)
            case LINE_V_REV | CROSSING_REV ⇒ connect(CORNER_TL_REV, CORNER_BR_REV)
            case g ⇒
              (j + 1).times(currLineGlyphs.insert(ix, if (hasOpenLeft(g)) LINE_H else SPACE))
          }
        } else if (!hasOpenTop(currLineGlyph) && hasOpenBottom(prevLineGlyph)) {
          // handled in the upward pass later
        } else if (ix < math.max(currLineGlyphs.size, prevLineGlyphs.size)) rec(ix + 1)
      }
      rec(0)
    }

    // STEP 4: connect shifts upwards
    for (currentIx ← rows.indices.reverse drop 1) {
      val currLineGlyphs = rows(currentIx).glyphs
      val nextLineGlyphs = rows(currentIx + 1).glyphs

      @tailrec def rec(ix: Int): Unit = {
        val currLineGlyph = glyphAt(currLineGlyphs, ix)
        val nextLineGlyph = glyphAt(nextLineGlyphs, ix)
        if (hasOpenBottom(currLineGlyph) && !hasOpenTop(nextLineGlyph)) {
          val j = Range(ix + 1, nextLineGlyphs.size).indexWhere(i ⇒ hasOpenTop(nextLineGlyphs(i)))
          def connect(leftGlyph: Glyph, cornerTR: Glyph): Unit = {
            currLineGlyphs(ix) = leftGlyph
            currLineGlyphs.insert(ix + 1, cornerTR)
            j.times(currLineGlyphs.insert(ix + 1, LINE_H))
          }
          currLineGlyph match {
            case NODE                      ⇒ connect(NODE_CORNER_BL, CORNER_TR)
            case LINE_V | CROSSING         ⇒ connect(CORNER_BL, CORNER_TR)
            case LINE_V_REV | CROSSING_REV ⇒ connect(CORNER_BL_REV, CORNER_TR_REV)
            case g ⇒
              (j + 1).times(currLineGlyphs.insert(ix, if (hasOpenLeft(g)) LINE_H else SPACE))
          }
        } else if (ix < math.max(currLineGlyphs.size, nextLineGlyphs.size)) rec(ix + 1)
      }
      rec(0)
    }

    rows
  }
}
