/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.graph.impl

import scala.annotation.tailrec
import scala.collection.mutable
import swave.core.graph.Digraph
import swave.core.macros._
import swave.core.util._
import Infrastructure._

private[graph] object CycleBreaking {

  private type Cycle = mutable.BitSet

  private final class CycleEdge(val id: Int, val edge: Edge, edgeAttrs: EdgeAttrs) {
    private[this] val reversaphile = if (edgeAttrs.has(edge, Digraph.EdgeAttributes.Reversaphile)) 1 else 0
    var cycleCount: Int            = _
    def sortValue                  = cycleCount * 2 + reversaphile

    override def toString = s"CycleEdge(id=$id, edge=${format(edge)}, cycleCount=$cycleCount, sortValue=$sortValue)"
  }

  // we employ a rather simple but relatively stable and predictable cycle breaking algorithm:
  // 1. Perform a Depth-First-Search starting from all root nodes and identify all edges whose target node is an
  //    ancestor of its origin, this lets us collect all graph cycles
  // 2. For each edge of every cycle, keep a counter of the number of times that this edge participates in a cycle
  //    (the edge's "cycle-count")
  // 3. While we still have unbroken cycles:
  //    reverse the edge with the highest cycle-count, as a tie-breaker prefer the ones marked "reversaphile"
  def reverseBackEdges[V](graph: GraphData[V]): GraphData[V] = {
    val cycles      = new mutable.ArrayBuffer[Cycle]
    val cycleEdges  = new mutable.ArrayBuffer[CycleEdge]
    var edgeMap     = Map.empty[Edge, CycleEdge]
    val ancestorSet = new mutable.BitSet

    def collectCycles(node: Node, ancestors: List[Node]): Unit =
      if (!ancestorSet.contains(node.id)) {
        ancestorSet += node.id
        val newAncestors = node :: ancestors
        (node.succs: Seq[Node]) match {
          case Nil ⇒ // leaf
          case Seq(next) ⇒
            collectCycles(next, newAncestors) // reduce stack pressure by pulling out the most frequent case
          case nodes ⇒ nodes.foreach(collectCycles(_, newAncestors))
        }
        ancestorSet -= node.id
        ()
      } else { // we found a cycle
        val cycle = new mutable.BitSet
        cycles += cycle
        var target = node
        for (origin ← ancestors.take(ancestors.indexOf(node) + 1)) {
          val edge = origin → target
          val cycleEdge = edgeMap.getOrElse(edge, {
            val ce = new CycleEdge(cycleEdges.size, edge, graph.edgeAttrs)
            cycleEdges += ce
            edgeMap = edgeMap.updated(edge, ce)
            ce
          })
          cycleEdge.cycleCount += 1
          cycle += cycleEdge.id
          target = origin
        }
      }

    for (root ← graph.rootNodes) collectCycles(root, Nil)

    @tailrec def breakCycles(g: GraphData[V]): GraphData[V] =
      if (cycles.nonEmpty) {
        val topEdge = cycleEdges.maxBy(_.sortValue)
        cycles.removeWhere { cycle ⇒
          cycle.contains(topEdge.id) && {
            for (id ← cycle) cycleEdges(id).cycleCount -= 1
            true
          }
        }
        breakCycles(reverseEdge(topEdge.edge, g))
      } else g

    breakCycles(graph)
  }

  private def reverseEdge[V](edge: Edge, graph: GraphData[V]): GraphData[V] = {
    import Digraph.EdgeAttributes._
    requireState(!graph.edgeAttrs.has(edge, Reversed))
    val revEdge = edge._2 → edge._1
    val g1      = removeEdge(edge, graph)
    val g2      = addEdge(revEdge, g1)
    val a1      = graph.edgeAttrs.add(revEdge, Reversed)
    val a2      = a1.move(edge, revEdge :: Nil, ~Fusable) // reversed edges are not fusable anymore
    g2.copy(edgeAttrs = a2)
  }

  private def removeEdge[V](edge: Edge, graph: GraphData[V]): GraphData[V] = {
    val (origin, target) = edge
    target.preds.removeIfPresent(origin)
    origin.succs.removeIfPresent(target)
    if (target.isRoot) graph.copy(rootNodes = graph.rootNodes :+ target) else graph
  }

  private def addEdge[V](edge: Edge, graph: GraphData[V]): GraphData[V] = {
    val (origin, target) = edge
    val targetWasRoot    = target.isRoot
    origin.succs += target
    target.preds += origin
    if (targetWasRoot) graph.copy(rootNodes = graph.rootNodes.filter(_ != target)) else graph
  }
}
