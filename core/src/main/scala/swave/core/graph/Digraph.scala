/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.graph

import scala.collection.immutable.BitSet
import scala.collection.mutable
import swave.core.graph.impl._
import swave.core.macros._
import swave.core.util._

/**
 * A very basic model for a Directed Graph, providing only what is required for rendering (prepation),
 * not general graph manipulation or analysis.
 */
final class Digraph[V](private var graphData: GraphData[V]) {
  import MiscLogic._

  // TODO: implement root creation in case the graph has no root initially, e.g. like this:
  // - find a reversaphile edge whose reversal creates a root and reverse it
  // - otherwise: find a regular edge whose reversal creates a root and reverse it
  // - otherwise: find a set of two edges whose reversals creates a root and reverse them (again prefer reversaphiles)
  // - continue until a root can be created
  requireArg(
    graphData.rootNodes.nonEmpty,
    "This rendering logic currently requires that the graph has at least one root vertex!")

  /**
   * The set of (distinct) vertices of the graph.
   */
  def vertices: Vector[V] = graphData.vertices

  /**
   * Apply the given attributes to all edges of all paths leading from the `origin` vertex to the `target` vertex.
   * If there is no path from `origin` to `target` or `origin` and `target` are the same vertex
   * then this method has no effect.
   */
  def markPaths(origin: V, target: V, attrs: Digraph.EdgeAttributes): Unit =
    graphData = graphData.copy(edgeAttrs =
      markNodePaths(graphData.edgeAttrs, graphData nodeFor origin, graphData nodeFor target, attrs))

  def markHidden(predicate: V ⇒ Boolean): Unit =
    for (node ← graphData.nodes) if (predicate(node.vertex.asInstanceOf[V])) node.isHidden = true

  def addVertexAttributes(bitSet: BitSet, attr: AnyRef): Unit =
    for (node ← graphData.nodes) if (bitSet contains node.id) node.attributes ::= attr

  def attributes(vertex: V): List[AnyRef] = graphData.nodeFor(vertex).attributes

  def discoverRegion(entries: Seq[V], exits: Seq[V]): BitSet =
    MiscLogic.discoverRegion(entries.map(graphData.nodeFor), exits.map(graphData.nodeFor))

  def xRankOrdering: Ordering[V] = {
    preparedGraphData // trigger xRanking
    MiscLogic.xRankOrdering(graphData)
  }

  def isMarked(vertex: V, bitSet: BitSet): Boolean = bitSet contains graphData.nodeFor(vertex).id

  def migrateVertexSet(otherGraph: Digraph[_], bitSet: BitSet): BitSet = {
    val result = new mutable.BitSet
    for (node ← otherGraph.graphData.nodes)
      if (bitSet contains node.id) {
        graphData.vertexMap.get(node.vertex.asInstanceOf[V]) match {
          case Some(n) ⇒ result += n.id
          case None    ⇒
        }
      }
    BitSet.empty | result
  }

  /**
   * Renders the graph into a [[Digraph.Rendering]] instance using the given [[GlyphSet]].
   */
  def render(glyphSet: GlyphSet = GlyphSet.`3x3 ASCII`): Digraph.Rendering[V] =
    LineRendering.renderLines(renderingRows, glyphSet)

  private[this] lazy val renderingRows: Vector[Infrastructure.Node] = {
    val topoOrdering = topoSort(preparedGraphData.rootNodes)
    requireState(topoOrdering.size == preparedGraphData.nodes.size) // otherwise we still have cycles
    GlyphLayout.layoutRows(topoOrdering, preparedGraphData.edgeAttrs)
  }

  private[this] lazy val preparedGraphData: GraphData[V] = {
    val g1 = CycleBreaking.reverseBackEdges(graphData)
    val g2 = fuseNodes(g1)
    val g3 = injectSyntheticNodes(g2)
    val g = g3.copy(rootNodes = g3.rootNodes.sortBy(-descendantCount(_)))
    XRanking.assignXRanks(g.rootNodes, g.nodes)
    g
  }
}

object Digraph {

  /**
   * Constructs a [[Digraph]] instance from a single vertex and a means to discover the complete graph starting from
   * that given entry vertex.
   *
   * Self edges are ignored.
   */
  def apply[V](entryVertex: V, predecessors: V ⇒ Seq[V], successors: V ⇒ Seq[V]): Digraph[V] =
    new Digraph[V](GraphData(entryVertex, predecessors, successors))

  type EdgeAttributes = Int
  object EdgeAttributes {
    final val All = 0xFFFFFFFF
    final val Fusable = 1
    final val Reversaphile = 2
    private[core] final val Reversed = 4
  }

  final case class Rendering[V](
      glyphSet: GlyphSet,
      maxLineLength: Int,
      vertexRenderings: Seq[VertexRendering[V]]) {

    def linesPerVertex: Int = glyphSet.rows

    def format(renderVertex: V ⇒ String, nodeRow: Int = (linesPerVertex - 1) / 2): String = {
      val sb = new java.lang.StringBuilder
      for (r @ VertexRendering(n, lines) ← vertexRenderings) {
        lines foreachWithIndex { (line, ix) ⇒
          if (line.nonEmpty || ix == nodeRow) {
            if (sb.length > 0) sb.append('\n')
            sb.append(line)
            if (ix == nodeRow) {
              sb.append(' ').append(renderVertex(n))
              ()
            }
          }
        }
      }
      sb.toString
    }
  }

  final case class VertexRendering[V](vertex: V, lines: Seq[String])
}
