/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.graph.impl

import scala.annotation.tailrec
import scala.util.control.NoStackTrace
import scala.collection.immutable.{ BitSet, VectorBuilder }
import scala.collection.mutable
import swave.core.graph.Digraph
import swave.core.macros._
import swave.core.util._
import Infrastructure._

private[graph] object MiscLogic {

  // injects synthetic nodes between fan-in/outs, where required;
  // direct connections from a fan-X origin to a fan-X target trip up certain bits in the downstream analysis
  // so we inject synthetic nodes whose rendering is later suppressed
  def injectSyntheticNodes[V](graph: GraphData[V]): GraphData[V] = {
    var nodes = graph.nodes
    var edgeAttrs = graph.edgeAttrs

    for (origin ← graph.nodes.withFilter(n ⇒ n.isFanIn || n.isFanOut)) {
      origin.succs.foreachWithIndex { (target, succIx) ⇒
        if (target.isFanIn || target.isFanOut) {
          val vertex = origin.vertex // the vertex of the synthetic node doesn't appear anywhere, so we just use this one
          val syntheticNode = new Node(nodes.size, vertex)
          syntheticNode.isHidden = true
          origin.succs(succIx) = syntheticNode
          target.preds(target.preds indexOf origin) = syntheticNode
          syntheticNode.preds += origin
          syntheticNode.succs += target
          nodes :+= syntheticNode
          edgeAttrs = edgeAttrs.move(origin → target, (origin → syntheticNode) :: (syntheticNode → target) :: Nil)
        }
      }
    }
    graph.copy(nodes = nodes, edgeAttrs = edgeAttrs)
  }

  // fuses two adjacent nodes if the (single) edge between them is marked fusable,
  // fusing merges the origin node of the edge into the target node (the origin node is linked out but not removed)
  def fuseNodes[V](graph: GraphData[V]): GraphData[V] = {
    var nodesToRemove = new mutable.BitSet
    var edgeAttrs = graph.edgeAttrs
    var rootNodes = graph.rootNodes

    for ((edge, flags) ← graph.edgeAttrs) {
      if ((flags & Digraph.EdgeAttributes.Fusable) != 0) {
        val (origin, target) = edge
        if (origin.succs.size == 1 && target.preds.size == 1) {
          for (p ← origin.preds) p.succs(p.succs indexOf origin) = target
          for (s ← origin.succs) if (s != target) s.preds(s.preds indexOf origin) = target
          val targetPreds = target.preds.clone()
          val targetSuccs = target.succs.clone()
          target.preds.clear()
          target.succs.clear()
          target.preds ++= targetPreds.flatMap(p ⇒ if (p == origin) origin.preds else p :: Nil)
          target.succs ++= origin.succs.flatMap(s ⇒ if (s == target) targetSuccs else s :: Nil)
          nodesToRemove += origin.id
          edgeAttrs -= edge
          if (origin.isRoot) rootNodes = rootNodes.updated(rootNodes indexOf origin, target)
          else origin.preds.foreach(p ⇒ edgeAttrs = edgeAttrs.move(p → origin, (p → target) :: Nil))
        }
      }
    }
    graph.copy(
      nodes = graph.nodes.filterNot(n ⇒ nodesToRemove contains n.id),
      rootNodes = rootNodes,
      edgeAttrs = edgeAttrs)
  }

  // rather simple topological sort, adapted from the one Kahn proposed in 1962,
  // which appears to be working quite well for our use case
  def topoSort(rootNodes: Vector[Node]): Vector[Node] = {
    val builder = new VectorBuilder[Node]

    @tailrec def rec(remainingRoots: List[Node]): Unit =
      if (remainingRoots.nonEmpty) {
        val root = remainingRoots.head
        builder += root
        val succs = if (root.succs.size > 1) root.succs.sortBy(-descendantCount(_)) else root.succs
        val newRemainingRoots = // prepend all succs to the remainingRoot that have zero unvisited predecessors
          succs.foldLeft(remainingRoots.tail) { (acc, succ) ⇒
            succ.inDegree = (if (succ.inDegree == -1) succ.preds.size else succ.inDegree) - 1
            if (succ.inDegree == 0) succ :: acc else acc
          }
        rec(newRemainingRoots)
      }

    rec(rootNodes.toList)
    builder.result()
  }

  // marks all paths from `origin` to `target` with `attrs`
  def markNodePaths(edgeAttrs: EdgeAttrs, origin: Node, target: Node, attrs: Digraph.EdgeAttributes): EdgeAttrs = {
    val seen = new mutable.BitSet

    def _rec(result: EdgeAttrs, node: Node, revPath: List[Node]) = rec(result, node, revPath)
    @tailrec def rec(result: EdgeAttrs, node: Node, revPath: List[Node]): EdgeAttrs =
      if (node != target) {
        if (!seen.contains(node.id)) {
          seen += node.id
          val revPath2 = node :: revPath
          (node.succs: Seq[Node]) match {
            case Nil       ⇒ result // leaf
            case Seq(next) ⇒ rec(result, next, revPath2)
            case nodes     ⇒ nodes.foldLeft(result)(_rec(_, _, revPath2))
          }
        } else result // just backtrack
      } else {
        var to = target
        revPath.foldLeft(result) { (ea, from) ⇒
          val edge = from → to
          to = from
          ea.add(edge, attrs)
        }
      }

    rec(edgeAttrs, origin, Nil)
  }

  val descendantCount: Node ⇒ Int = {
    val bitSet = new mutable.BitSet
    val treeMarker = markTree(bitSet, _.succs)
    node ⇒ {
      if (node.desCount == -1) node.desCount = {
        bitSet.clear()
        treeMarker(node)
        bitSet.size
      }
      node.desCount
    }
  }

  def findCycle(rootNodes: Seq[Node]): Option[Edge] = {
    requireArg(rootNodes.nonEmpty)
    val seen = new mutable.BitSet
    val open = new scala.collection.mutable.BitSet
    val rec = new (Node ⇒ Option[Edge]) {
      def apply(node: Node): Option[Edge] = {
        if (!open.contains(node.id)) {
          open += node.id
          val result =
            if (!seen.contains(node.id)) {
              (node.succs: Seq[Node]) match {
                case Nil       ⇒ None // leaf
                case Seq(next) ⇒ apply(next) // reduce stack pressure by pulling out the most frequent case
                case nodes     ⇒ nodes.mapFind(this)
              }
            } else None
          seen += node.id
          open -= node.id
          result match {
            case Some((null, target)) ⇒ Some(node → target)
            case x                    ⇒ x
          }
        } else Some((null, node))
      }
    }
    rootNodes.mapFind(rec)
  }

  def markTree[V](bitSet: mutable.BitSet, f: Node ⇒ Seq[Node]): Node ⇒ Unit =
    new (Node ⇒ Unit) {
      @tailrec def apply(node: Node): Unit = {
        if (!bitSet.contains(node.id)) {
          bitSet += node.id
          (f(node): Seq[Node]) match {
            case Nil       ⇒ // leaf
            case Seq(next) ⇒ apply(next)
            case nodes     ⇒ nodes.foreach(this)
          }
        }
      }
    }

  def xRankOrdering[V](graph: GraphData[V]): Ordering[V] =
    new Ordering[V] {
      def compare(a: V, b: V): Int = {
        val nodeA = graph nodeFor a
        val nodeB = graph nodeFor b
        val sameGroupComp =
          if (nodeA.xRank.group == nodeB.xRank.group) Integer.compare(nodeA.xRank.level, nodeB.xRank.level) else 0
        if (sameGroupComp == 0) {
          // TODO: implement xrank ordering for nodes that are not in the same xRankGroup by analyzing all paths
          // between the nodes
          Integer.compare(nodeA.id, nodeB.id) // use simple stable ordering as a temporary solution
        } else sameGroupComp
      }
    }

  /**
   * Discovers a node region that is "fenced off" from the rest of the graph by the given boundaries.
   *
   * If the region is sound, i.e. it's impossible to reach an entry node from above or an exit node from below
   * without previously crossing a boundary, the method returns a bitset marking all region nodes.
   * If the region is unsound the method returns an empty BitSet.
   */
  def discoverRegion(boundaries: Seq[Digraph.RegionBoundary[Node]]): BitSet = {
    val result = new mutable.BitSet
    val entriesSet = new mutable.BitSet
    val exitsSet = new mutable.BitSet
    boundaries.foreach { b1 ⇒
      if (b1.isEntry) entriesSet += b1.vertex.id else exitsSet += b1.vertex.id
      boundaries.foreach { b2 ⇒
        requireArg(b1 == b2 || b1.vertex != b2.vertex || b1.isInner && b2.isInner, s"Inconsistent RegionBoundaries: $b1 and $b2")
      }
    }

    @tailrec def rec(remaining: List[Node]): BitSet =
      if (remaining.nonEmpty) {
        if (!result.contains(remaining.head.id)) {
          result += remaining.head.id
          val tail1 =
            if (!entriesSet.contains(remaining.head.id)) {
              remaining.head.preds.foldLeft(remaining.tail) { (acc, p) ⇒
                def pIsOuterEntry = Digraph.RegionBoundary(p, isEntry = true, isInner = false)
                if (result.contains(p.id) || (entriesSet.contains(p.id) && boundaries.contains(pIsOuterEntry))) acc
                else if (exitsSet contains p.id) throw UnsoundRegion else p :: acc
              }
            } else remaining.tail
          val tail2 =
            if (!exitsSet.contains(remaining.head.id)) {
              remaining.head.succs.foldLeft(tail1) { (acc, s) ⇒
                def sIsOuterExit = Digraph.RegionBoundary(s, isEntry = false, isInner = false)
                if (result.contains(s.id) || (exitsSet.contains(s.id) && boundaries.contains(sIsOuterExit))) acc
                else if (entriesSet contains s.id) throw UnsoundRegion else s :: acc
              }
            } else tail1
          rec(tail2)
        } else rec(remaining.tail)
      } else BitSet.empty | result

    try {
      rec(boundaries.collect { case b if b.isInner ⇒ b.vertex }(collection.breakOut))
    } catch {
      case UnsoundRegion ⇒ BitSet.empty
    }
  }

  private object UnsoundRegion extends RuntimeException with NoStackTrace
}
