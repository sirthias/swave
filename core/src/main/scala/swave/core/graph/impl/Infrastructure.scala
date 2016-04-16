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

import scala.collection.mutable
import swave.core.graph.{ Digraph, Glyph }
import swave.core.util._

private[graph] object Infrastructure {

  type Edge = (Node, Node)

  final class Node(val id: Int, val vertex: Any) {
    val preds = new mutable.ArrayBuffer[Node]
    val succs = new mutable.ArrayBuffer[Node]

    def isSingle = preds.isEmpty && succs.isEmpty
    def isRoot = preds.isEmpty
    def isLeaf = succs.isEmpty
    def isInOut = preds.size == 1 && succs.size == 1
    def isFanIn = preds.size > 1
    def isFanOut = succs.size > 1

    var isHidden = false
    var desCount = -1
    var inDegree = -1
    var xRank: XRank = _
    val glyphs = new mutable.ArrayBuffer[Glyph]
    var attributes = List.empty[AnyRef]

    override def toString =
      s"Node(vertex=$vertex, id=$id, rankGroup=${if (xRank != null && xRank.group != null) xRank.group.groupId else "null"}, " +
        s"attrs=${attributes.mkString("[", ",", "]")}, " +
        s"preds=${preds.map(_.id).mkString("[", ",", "]")}, " +
        s"succs=${succs.map(_.id).mkString("[", ",", "]")}" + (if (isHidden) ", hidden)" else ")")
  }

  final class XRank(val id: Int) {
    var group: XRankGroup = _
    var level = -1 // smaller values -> lay out to the left, higher values -> lay out to the right
    var preds = List.empty[XRank]
    var succs = List.empty[XRank]

    override def toString = s"XRank(id=$id, group=${group.groupId}, level=$level, " +
      s"preds=[${preds.map(_.id).mkString(",")}], succs=[${succs.map(_.id).mkString(",")}])"
  }

  final class XRankGroup(var groupId: Int) {
    override def equals(that: Any): Boolean =
      that.isInstanceOf[XRankGroup] && that.asInstanceOf[XRankGroup].groupId == groupId
    override def hashCode() = groupId
  }

  type EdgeAttrs = Map[Edge, Digraph.EdgeAttributes]

  implicit class RichEdgeAttrs(val underlying: EdgeAttrs) extends AnyVal {
    def get(edge: Edge): Digraph.EdgeAttributes = underlying.getOrElse(edge, 0)
    def has(edge: Edge, attrs: Digraph.EdgeAttributes): Boolean = (get(edge) & attrs) != 0
    def add(edge: Edge, attrs: Digraph.EdgeAttributes): EdgeAttrs = underlying.updated(edge, get(edge) | attrs)
    def move(sourceEdge: Edge, targetEdges: List[Edge], filter: Int = Digraph.EdgeAttributes.All): EdgeAttrs =
      underlying.get(sourceEdge) match {
        case None ⇒ underlying
        case Some(flags) ⇒
          val filtered = flags & filter
          val map = if (filtered != 0) targetEdges.foldLeft(underlying)(_ add (_, filtered)) else underlying
          map - sourceEdge
      }

    def printAll() = {
      for ((edge, flags) ← underlying) println(format(edge) + ": " + flags)
      println()
    }
  }

  val Root: AnyRefExtractor[Node, Seq[Node]] =
    AnyRefExtractor(n ⇒ if (n.isRoot) n.succs else null)

  val Leaf: AnyRefExtractor[Node, Seq[Node]] =
    AnyRefExtractor(n ⇒ if (n.isLeaf) n.preds else null)

  val InOut: AnyRefExtractor[Node, (Node, Node)] =
    AnyRefExtractor(n ⇒ if (n.isInOut) n.preds.head → n.succs.head else null)

  def format(edge: Edge) = s"[${edge._1.id} -> ${edge._2.id}]"
}
