/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.graph.impl

import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder
import swave.core.graph.Digraph
import Infrastructure._

/**
  * The basic data model of our graph representation.
  *
  * Terminology: What is called "Vertex" on the user-facing API is internally called "Node", i.e.
  * the graph elements that are connected by edges.
  * To the implementation vertices of type `V` are completely opaque. Internally they are wrapped into
  * (mutable) `GraphNode` instances, which hold everything we need to efficiently work on the graph.
  */
private[graph] final case class GraphData[V](vertices: Vector[V],
                                             vertexMap: Map[V, Node],
                                             nodes: Vector[Node],
                                             rootNodes: Vector[Node],
                                             edgeAttrs: EdgeAttrs) {

  def nodeFor(vertex: V): Node =
    vertexMap.getOrElse(vertex, throw new IllegalArgumentException(s"Unknown vertex [$vertex]"))

  def printNodes() = {
    for (n ← nodes) println(n)
    println()
  }

  def mapVertices[VV](f: V ⇒ VV): GraphData[VV] = {
    var vertexTranslation = Map.empty[V, VV]
    val newVertices = vertices.map { v ⇒
      val vv = f(v)
      vertexTranslation = vertexTranslation.updated(v, vv)
      vv
    }
    var nodeTranslation = Map.empty[Node, Node]
    val newNodes = nodes.map { n ⇒
      val nn = n.partialCopyWith(vertexTranslation(n.vertex.asInstanceOf[V]))
      nodeTranslation = nodeTranslation.updated(n, nn)
      nn
    }
    nodes.foreach { n ⇒
      val nn = nodeTranslation(n)
      n.preds.foreach(p ⇒ nn.preds += nodeTranslation(p))
      n.succs.foreach(s ⇒ nn.succs += nodeTranslation(s))
    }
    GraphData(
      vertices = newVertices,
      vertexMap = vertices.foldLeft(Map.empty[VV, Node])((map, v) ⇒ map.updated(vertexTranslation(v), vertexMap(v))),
      nodes = newNodes,
      rootNodes = rootNodes.map(nodeTranslation),
      edgeAttrs = edgeAttrs.foldLeft(Map.empty[Edge, Digraph.EdgeAttributes]) {
        case (map, ((from, to), attrs)) ⇒ map.updated(nodeTranslation(from) → nodeTranslation(to), attrs)
      })
  }
}

private[graph] object GraphData {

  def apply[V](entryVertices: Iterable[V], predecessors: V ⇒ Seq[V], successors: V ⇒ Seq[V]): GraphData[V] = {
    var vertexMap        = Map.empty[V, Node]
    val nodeBuilder      = new VectorBuilder[Node]
    val nodeIds          = Iterator from 0
    val rootNodesBuilder = new VectorBuilder[Node]

    def getOrCreateNode(value: V): Node =
      vertexMap.getOrElse(value, {
        val node = new Node(nodeIds.next(), value)
        vertexMap = vertexMap.updated(value, node)
        nodeBuilder += node
        node
      })

    def _rec(origin: Node): Node ⇒ Unit = rec(_, origin)
    @tailrec def rec(node: Node, origin: Node): Unit =
      if (node.isSingle) {
        for (p ← predecessors(node.vertex.asInstanceOf[V])) {
          val n = getOrCreateNode(p)
          if (n != node) node.preds += n // ignore self edges
        }
        for (s ← successors(node.vertex.asInstanceOf[V])) {
          val n = getOrCreateNode(s)
          if (n != node) node.succs += n // ignore self edges
        }
        node match {
          case InOut(`origin`, next) ⇒ rec(next, node)
          case InOut(next, `origin`) ⇒ rec(next, node)
          case _ ⇒
            if (node.isRoot) rootNodesBuilder += node
            val recurse = _rec(node)
            node.preds.foreach(recurse)
            node.succs.foreach(recurse)
        }
      }

    for (v ← entryVertices) rec(getOrCreateNode(v), null)

    // verify complete reciprocity of preds and succs assignments
    val nodes = nodeBuilder.result()
    for (node ← nodes) {
      def fail(msg: String) = {
        for (n ← nodes) println(n)
        println()
        throw new IllegalStateException(msg)
      }

      node.preds foreach { p ⇒
        val a = node.preds.count(_ == p)
        val b = p.succs.count(_ == node)
        if (a != b)
          fail(
            s"Vertices A=[${node.vertex}] and B=[${p.vertex}] have inconsistent edge data, " +
              s"A lists B as predecessor $a time(s) but B lists A as successor $b time(s)!")
      }
      node.succs foreach { s ⇒
        val a = node.succs.count(_ == s)
        val b = s.preds.count(_ == node)
        if (a != b)
          fail(
            s"Vertices A=[${node.vertex}] and B=[${s.vertex}] have inconsistent edge data, " +
              s"A lists B as successor $a time(s) but B lists A as predecessor $b time(s)!")
      }
    }

    GraphData(vertexMap.keySet.toVector, vertexMap, nodes, rootNodesBuilder.result(), Map.empty)
  }
}
