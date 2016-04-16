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

import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder
import Infrastructure._

/**
 * The basic data model of our graph representation.
 *
 * Terminology: What is called "Vertex" on the user-facing API is internally called "Node", i.e.
 * the graph elements that are connected by edges.
 * To the implementation vertices of type `V` are completely opaque. Internally they are wrapped into
 * (mutable) `GraphNode` instances, which hold everything we need to efficiently work on the graph.
 */
private[graph] final case class GraphData[V](
    vertices: Vector[V],
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
}

private[graph] object GraphData {

  def apply[V](entryVertex: V, predecessors: V ⇒ Seq[V], successors: V ⇒ Seq[V]): GraphData[V] = {
    var vertexMap = Map.empty[V, Node]
    val nodeBuilder = new VectorBuilder[Node]
    val nodeIds = Iterator from 0
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

    rec(getOrCreateNode(entryVertex), null)

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
          fail(s"Vertices A=[${node.vertex}] and B=[${p.vertex}] have inconsistent edge data, " +
            s"A lists B as predecessor $a time(s) but B lists A as successor $b time(s)!")
      }
      node.succs foreach { s ⇒
        val a = node.succs.count(_ == s)
        val b = s.preds.count(_ == node)
        if (a != b)
          fail(s"Vertices A=[${node.vertex}] and B=[${s.vertex}] have inconsistent edge data, " +
            s"A lists B as successor $a time(s) but B lists A as predecessor $b time(s)!")
      }
    }

    GraphData(vertexMap.keySet.toVector, vertexMap, nodes, rootNodesBuilder.result(), Map.empty)
  }
}
