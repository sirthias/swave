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
import scala.collection.mutable
import Infrastructure._

private[graph] object XRanking {

  def assignXRanks(rootNodes: Vector[Node], allNodes: Vector[Node]): Unit = {

    // STEP 1: partition the nodes into group which have identical XRanks
    // by assigning them the same XRank instance
    val ranks = new mutable.ArrayBuffer[XRank];
    {
      def createXRank(): XRank = {
        val rank = new XRank(ranks.size)
        ranks += rank
        rank
      }

      def _visit(node: Node, rank: XRank): Unit = visit(node, rank)
      @tailrec def visit(node: Node, rank: XRank): Unit =
        if (node.xRank eq null) {
          val nodeRank = if (node.isFanIn) createXRank() else rank
          node.xRank = nodeRank
          (node.succs: Seq[Node]) match {
            case Nil       ⇒ // leaf, just backtrack
            case Seq(next) ⇒ visit(next, nodeRank)
            case succs     ⇒ succs.foreach(_visit(_, createXRank()))
          }
        }

      for (root ← rootNodes) visit(root, createXRank())
    }

    // STEP 2: connect the created XRank instances with edges whereby
    // an edge from rank `a` to rank `b` means "a should be laid out to the left of b"
    // which results in the XRank instances being structured into a graph forest
    {
      val connectRanks: (Node, Node) ⇒ Node = { (a, b) ⇒
        a.xRank.succs ::= b.xRank
        b.xRank.preds ::= a.xRank
        b
      }
      for (node ← allNodes) {
        if (node.isFanIn) node.preds.reduceLeft(connectRanks)
        if (node.isFanOut) node.succs.reduceLeft(connectRanks)
      }
    }

    // STEP3: identify the connected parts of the rank forest (i.e. the rank graphs)
    // and mark all nodes of a connected part with the same (value equality) XRankGroup
    {
      val groupIds = Iterator.from(0)
      def assingGroup(rank: XRank, group: XRankGroup): Unit =
        if (rank.group eq null) {
          rank.group = group
          rank.preds.foreach(assingGroup(_, group))
        } else group.groupId = rank.group.groupId // merge the two groups

      ranks.withFilter(_.succs.isEmpty).foreach(assingGroup(_, new XRankGroup(groupIds.next())))
    }

    // STEP4: for each XRankGroup: apply a simple layering algorithm
    {
      val bitSet = new mutable.BitSet(ranks.size)

      for (groupRanks ← ranks.groupBy(_.group).valuesIterator) {

        def assignLevel(rank: XRank, level: Int): Unit =
          if (!bitSet.contains(rank.id)) {
            bitSet += rank.id
            if (level > rank.level) rank.level = level
            rank.succs.foreach(assignLevel(_, level + 1))
            bitSet -= rank.id
            ()
          } else println("XRank crossing!")
        groupRanks.withFilter(_.preds.isEmpty).foreach(assignLevel(_, 0))

        def compactLevels(rank: XRank): Boolean =
          !bitSet.contains(rank.id) && {
            bitSet += rank.id
            val minSubRank = if (rank.succs.nonEmpty) rank.succs.minBy(_.level).level else 0
            val progress = rank.level < minSubRank - 1 && { rank.level = minSubRank - 1; true }
            val result = rank.succs.foldRight(progress)(compactLevels(_) || _)
            bitSet -= rank.id
            result
          }
        val leafs = groupRanks.filter(_.succs.isEmpty)
        while (leafs.foldRight(false)(compactLevels(_) || _)) ()
      }
    }
  }
}
