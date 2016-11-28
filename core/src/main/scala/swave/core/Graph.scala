/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import scala.annotation.tailrec
import scala.collection.immutable.BitSet
import scala.collection.mutable
import swave.core.graph.{Digraph, GlyphSet}
import swave.core.macros._
import swave.core.util._

object Graph {

  type Vertex = Either[Module.ID, Stage]

  trait ExpandModules extends (Module.ID ⇒ Boolean)
  object ExpandModules {
    def apply(predicate: Module.ID ⇒ Boolean): ExpandModules =
      new ExpandModules {
        def apply(id: Module.ID) = predicate(id)
      }
    val None                      = ExpandModules(_ ⇒ false)
    val All                       = ExpandModules(_ ⇒ true)
    def Some(names: List[String]) = ExpandModules(id ⇒ names contains id.name)
  }

  def render(stage: Stage,
             expandModules: ExpandModules = ExpandModules.None,
             glyphSet: GlyphSet = GlyphSet.`3x3 ASCII`,
             showParams: Boolean = false,
             showRunners: Boolean = false,
             showNops: Boolean = false) = {

    val graph = create(stage, expandModules)
    if (!showNops) graph.markHidden { case Right(x) if x.kind == Stage.Kind.InOut.Nop ⇒ true; case _ ⇒ false }
    graph.render(glyphSet).format {
      case x @ Right(st) ⇒
        val label =
          if (showParams || showRunners) {
            val sb = new java.lang.StringBuilder(st.kind.name)
            if (showParams) sb.append(st.kind.productIterator.mkString("(", ", ", ")"))
            if (showRunners) sb.append(" ^").append(if (st.stageImpl.hasRunner) st.stageImpl.runner else "none")
            sb.toString
          } else st.kind.name
        graph.attributes(x) match {
          case Nil     ⇒ label
          case modules ⇒ label + modules.mkString(" [", ", ", "]")
        }
      case Left(moduleID) ⇒ s"{${moduleID.name}}"
    }
  }

  def explore(entryStage: Stage): Set[Stage] = {
    final class Rec extends (Stage ⇒ Unit) {
      var result                     = Set.empty[Stage]
      var seenModules                = Set.empty[Module.ID]
      var remainingEntryPoints       = Iterator.single(entryStage)
      def _apply(stage: Stage): Unit = apply(stage)
      @tailrec def apply(stage: Stage): Unit = {
        val newResult = result + stage
        if (newResult ne result) {
          result = newResult
          enqueueModuleBoundaries(stage.boundaryOf)
          3 * stage.inputStages.size012 + stage.outputStages.size012 match {
            case 0 /* 0:0 */ ⇒ throw new IllegalStateException // no input and no output?
            case 1 /* 0:1 */ ⇒ apply(stage.outputStages.head)
            case 2 /* 0:x */ ⇒ stage.outputStages.foreach(this)
            case 3 /* 1:0 */ ⇒ apply(stage.inputStages.head)
            case 4 /* 1:1 */ ⇒ { _apply(stage.inputStages.head); apply(stage.outputStages.head) }
            case 5 /* 1:x */ ⇒ { _apply(stage.inputStages.head); stage.outputStages.foreach(this) }
            case 6 /* x:0 */ ⇒ stage.inputStages.foreach(this)
            case 7 /* x:1 */ ⇒ { stage.inputStages.foreach(this); apply(stage.outputStages.head) }
            case 8 /* x:x */ ⇒ { stage.inputStages.foreach(this); stage.outputStages.foreach(this) }
          }
        }
      }
      @tailrec def enqueueModuleBoundaries(modules: List[Module.ID]): Unit =
        modules match {
          case head :: tail ⇒
            val newSeenModules = seenModules + head
            if (newSeenModules ne seenModules) {
              seenModules = newSeenModules
              remainingEntryPoints ++= head.boundaries.iterator.map(_.stage)
            }
            enqueueModuleBoundaries(tail)
          case _ ⇒ // done
        }
      @tailrec def run(): Set[Stage] =
        if (remainingEntryPoints.hasNext) {
          apply(remainingEntryPoints.next())
          run()
        } else result
    }
    new Rec().run()
  }

  def create(entryStage: Stage, expandModules: ExpandModules = ExpandModules.None): Digraph[Vertex] = {
    import Digraph.EdgeAttributes._

    ///////////////// STEP 1: discover complete graph ///////////////////////

    val allElems = explore(entryStage)
    val graph0   = Digraph[Stage](entryStage :: allElems.toList, _.inputStages, _.outputStages)

    ///////////////// STEP 2: identify modules, apply markers //////////////////////

    val allModuleInfos: List[ModuleInfo] =
      graph0.vertices
        .flatMap(_.boundaryOf)(BreakOutTo[Set].here) // similar to .flatMap(...).distinct but faster
        .flatMap { moduleID ⇒
          val regionBoundaries = moduleID.boundaries.map { x ⇒
            Digraph.RegionBoundary(x.stage, x.isEntry, x.isInner)
          }
          val regionBitSet = graph0.discoverRegion(regionBoundaries)
          if (regionBitSet.nonEmpty) {
            val modulePreds: List[Stage] = regionBoundaries.flatMap {
              case Digraph.RegionBoundary(stage, true, true)  ⇒ stage.inputStages
              case Digraph.RegionBoundary(stage, true, false) ⇒ stage :: Nil
              case _                                          ⇒ Nil
            }
            val moduleSuccs: List[Stage] = regionBoundaries.flatMap {
              case Digraph.RegionBoundary(stage, false, true)  ⇒ stage.outputStages
              case Digraph.RegionBoundary(stage, false, false) ⇒ stage :: Nil
              case _                                           ⇒ Nil
            }
            ModuleInfo(moduleID, regionBitSet, modulePreds, moduleSuccs) :: Nil
          } else Nil
        }(collection.breakOut)

    ////////////// STEP 3: filter first-level collapsed modules ////////////

    val visibleCollapsed = new mutable.ArrayBuffer[ModuleInfo]
    for (info ← allModuleInfos) {
      if (info.id.name.nonEmpty && !expandModules(info.id)) {
        visibleCollapsed.indexWhere(m ⇒ (m.vertices & info.vertices).nonEmpty) match {
          case -1 ⇒ visibleCollapsed += info
          case ix ⇒
            val alreadyStored = visibleCollapsed(ix)
            def containsAll(a: ModuleInfo, b: ModuleInfo) =
              (b.vertices &~ a.vertices).isEmpty // true if a contains all of b
            if (containsAll(info, alreadyStored)) visibleCollapsed(ix) = info
            else
              requireArg(
                containsAll(alreadyStored, info),
                s"Modules [${alreadyStored.id.name}] and [${info.id.name}] overlap without one fully containing" +
                  "the other, which is unsupported for rendering!")
        }
      }
    }

    ////////////// STEP 4: construct new graph with the collapsed modules ////////////

    def moduleOf(stage: Stage): Option[Module.ID] =
      visibleCollapsed collectFirst { case info if graph0.isMarked(stage, info.vertices) ⇒ info.id }

    if (visibleCollapsed.nonEmpty) {
      implicit val ordering = graph0.xRankOrdering // ordering for the .sortBy calls we have below
      var needSuccsPatched  = Set.empty[Stage]
      val modulePreds = visibleCollapsed.foldLeft(Map.empty[Module.ID, List[Vertex]]) {
        case (map, ModuleInfo(moduleID, _, mpreds, _)) ⇒
          val preds = mpreds map { p ⇒
            moduleOf(p) match {
              case Some(mid) ⇒ Left(mid) → p // p is pred of this module which lies itself in another module
              case None ⇒ // p is not part of another module but we need to patch its succs since they contain a module
                needSuccsPatched += p
                Right(p) → p
            }
          }
          map.updated(moduleID, preds.sortBy(_._2).map(_._1))
      }
      var needPredsPatched = Set.empty[Stage]
      val moduleSuccs = visibleCollapsed.foldLeft(Map.empty[Module.ID, List[Vertex]]) {
        case (map, ModuleInfo(moduleID, _, _, msuccs)) ⇒
          val succs = msuccs map { s ⇒
            moduleOf(s) match {
              case Some(mid) ⇒ Left(mid) → s // s is pred of this module which lies itself in another module
              case None ⇒ // s is not part of another module but we need to patch its preds since they contain a module
                needPredsPatched += s
                Right(s) → s
            }
          }
          map.updated(moduleID, succs.sortBy(_._2).map(_._1))
      }
      val elemSuccs = needSuccsPatched.foldLeft(Map.empty[Stage, List[Vertex]]) { (map, stage) ⇒
        map.updated(stage, stage.outputStages.map(s ⇒ moduleOf(s).fold[Vertex](Right(s))(Left(_))))
      }
      val elemPreds = needPredsPatched.foldLeft(Map.empty[Stage, List[Vertex]]) { (map, stage) ⇒
        map.updated(stage, stage.inputStages.map(p ⇒ moduleOf(p).fold[Vertex](Right(p))(Left(_))))
      }
      def preds: Vertex ⇒ Seq[Vertex] = {
        case Right(x) ⇒ elemPreds.getOrElse(x, x.inputStages.map(Right(_)))
        case Left(x)  ⇒ modulePreds(x)
      }
      def succs: Vertex ⇒ Seq[Vertex] = {
        case Right(x) ⇒ elemSuccs.getOrElse(x, x.outputStages.map(Right(_)))
        case Left(x)  ⇒ moduleSuccs(x)
      }

      val collapsedGraph = Digraph[Vertex](visibleCollapsed.map(info ⇒ Left(info.id)), preds, succs)
      for (info ← allModuleInfos) {
        collapsedGraph.addVertexAttributes(collapsedGraph.migrateVertexSet(graph0, info.vertices), info.id.name)
      }
      collapsedGraph.vertices.foreach {
        case x @ Right(stage) if stage.kind == Stage.Kind.InOut.Coupling ⇒
          collapsedGraph.markPaths(preds(x).head, succs(x).head, Reversaphile | Fusable)
        case _ ⇒
      }
      collapsedGraph
    } else {
      // all modules are expanded
      for (info ← allModuleInfos) graph0.addVertexAttributes(info.vertices, info.id.name)
      graph0.vertices.foreach { x =>
        if (x.kind == Stage.Kind.InOut.Coupling && x.inputStages.nonEmpty && x.outputStages.nonEmpty)
          graph0.markPaths(x.inputStages.head, x.outputStages.head, Reversaphile | Fusable)
      }
      graph0.mapVertices(Right(_))
    }
  }

  private case class ModuleInfo(id: Module.ID, vertices: BitSet, preds: List[Stage], succs: List[Stage])

}
