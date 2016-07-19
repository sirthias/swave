/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.collection.immutable.BitSet
import scala.collection.mutable
import swave.core.impl.stages.Stage
import swave.core.graph.{ GlyphSet, Digraph }
import swave.core.macros._
import swave.core.util._

object GraphRendering {

  type Vertex = Either[Module.ID, PipeElem]

  def apply(
    pipeElem: PipeElem,
    expandModules: List[String] = Nil,
    glyphSet: GlyphSet = GlyphSet.`3x3 ASCII`,
    showParams: Boolean = false,
    showRunners: Boolean = false,
    showNops: Boolean = false) = {

    val graph = assembleGraph(pipeElem, expandModules)
    if (!showNops) graph.markHidden { case Right(_: PipeElem.InOut.Nop) ⇒ true; case _ ⇒ false }
    graph.render(glyphSet).format {
      case x @ Right(elem) ⇒
        val label =
          if (showParams || showRunners) {
            val sb = new java.lang.StringBuilder(elem.pipeElemType)
            if (showParams) sb.append(elem.pipeElemParams.mkString("(", ", ", ")"))
            if (showRunners) {
              val runner = elem.asInstanceOf[Stage].runner
              sb.append(" ^").append(if (runner ne null) runner else "none")
            }
            sb.toString
          } else elem.pipeElemType
        graph.attributes(x) match {
          case Nil     ⇒ label
          case modules ⇒ label + modules.mkString(" [", ", ", "]")
        }
      case Left(moduleID) ⇒ s"{${moduleID.name}}"
    }
  }

  def assembleGraph(entryElem: PipeElem, expandModules: List[String] = Nil): Digraph[Vertex] = {
    import Digraph.EdgeAttributes._

    ///////////////// STEP 1: discover complete graph ///////////////////////

    val graph0 = Digraph[PipeElem](entryElem, _.inputElems, _.outputElems)

    ///////////////// STEP 2: identify modules, apply markers //////////////////////

    val allModuleInfos: List[ModuleInfo] =
      graph0.vertices
        .flatMap(_.boundaryOf)(BreakOutTo[Set].here) // similar to .flatMap(...).distinct but faster
        .flatMap { moduleID ⇒
          val regionBoundaries = moduleID.boundaries.map {
            case Module.Boundary(elem, isEntry, isInner) ⇒ Digraph.RegionBoundary(elem, isEntry, isInner)
          }
          val regionBitSet = graph0.discoverRegion(regionBoundaries)
          if (regionBitSet.nonEmpty) {
            val modulePreds: List[PipeElem] = regionBoundaries.flatMap {
              case Digraph.RegionBoundary(elem, true, true)  ⇒ elem.inputElems
              case Digraph.RegionBoundary(elem, true, false) ⇒ elem :: Nil
              case _                                         ⇒ Nil
            }
            val moduleSuccs: List[PipeElem] = regionBoundaries.flatMap {
              case Digraph.RegionBoundary(elem, false, true) ⇒ elem.outputElems
              case Digraph.RegionBoundary(elem, false, false) ⇒ elem :: Nil
              case _ ⇒ Nil
            }
            ModuleInfo(moduleID, regionBitSet, modulePreds, moduleSuccs) :: Nil
          } else Nil
        }(collection.breakOut)

    ////////////// STEP 3: filter first-level collapsed modules ////////////

    val visibleCollapsed = new mutable.ArrayBuffer[ModuleInfo]
    for (info ← allModuleInfos) {
      if (info.id.name.nonEmpty && !expandModules.contains(info.id.name)) {
        visibleCollapsed.indexWhere(m ⇒ (m.vertices & info.vertices).nonEmpty) match {
          case -1 ⇒ visibleCollapsed += info
          case ix ⇒
            val alreadyStored = visibleCollapsed(ix)
            def containsAll(a: ModuleInfo, b: ModuleInfo) = (b.vertices &~ a.vertices).isEmpty // true if a contains all of b
            if (containsAll(info, alreadyStored)) visibleCollapsed(ix) = info
            else requireArg(
              containsAll(alreadyStored, info),
              s"Modules [${alreadyStored.id.name}] and [${info.id.name}] overlap without one fully containing" +
                "the other, which is unsupported for rendering!")
        }
      }
    }

    ////////////// STEP 4: construct new graph with the collapsed modules ////////////

    def moduleOf(elem: PipeElem): Option[Module.ID] =
      visibleCollapsed collectFirst { case info if graph0.isMarked(elem, info.vertices) ⇒ info.id }

    visibleCollapsed.headOption match {
      case Some(collapsedModuleInfo) ⇒
        implicit val ordering = graph0.xRankOrdering // ordering for the .sortBy calls we have below
        var needSuccsPatched = Set.empty[PipeElem]
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
        var needPredsPatched = Set.empty[PipeElem]
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
        val elemSuccs = needSuccsPatched.foldLeft(Map.empty[PipeElem, List[Vertex]]) { (map, elem) ⇒
          map.updated(elem, elem.outputElems.map(s ⇒ moduleOf(s).fold[Vertex](Right(s))(Left(_))))
        }
        val elemPreds = needPredsPatched.foldLeft(Map.empty[PipeElem, List[Vertex]]) { (map, elem) ⇒
          map.updated(elem, elem.inputElems.map(p ⇒ moduleOf(p).fold[Vertex](Right(p))(Left(_))))
        }
        def preds: Vertex ⇒ Seq[Vertex] = {
          case Right(x) ⇒ elemPreds.getOrElse(x, x.inputElems.map(Right(_)))
          case Left(x)  ⇒ modulePreds(x)
        }
        def succs: Vertex ⇒ Seq[Vertex] = {
          case Right(x) ⇒ elemSuccs.getOrElse(x, x.outputElems.map(Right(_)))
          case Left(x)  ⇒ moduleSuccs(x)
        }

        val collapsedGraph = Digraph[Vertex](Left(collapsedModuleInfo.id), preds, succs)
        for (info ← allModuleInfos) {
          collapsedGraph.addVertexAttributes(collapsedGraph.migrateVertexSet(graph0, info.vertices), info.id.name)
        }
        collapsedGraph.vertices.foreach {
          case x @ Right(_: PipeElem.InOut.Coupling) ⇒
            collapsedGraph.markPaths(preds(x).head, succs(x).head, Reversaphile | Fusable)
          case _ ⇒
        }
        collapsedGraph

      case None ⇒ // all modules are expanded
        for (info ← allModuleInfos) graph0.addVertexAttributes(info.vertices, info.id.name)
        graph0.vertices.foreach {
          case x: PipeElem.InOut.Coupling ⇒ graph0.markPaths(x.inputElem, x.outputElem, Reversaphile | Fusable)
          case _                          ⇒
        }
        graph0.mapVertices(Right(_))
    }
  }

  private case class ModuleInfo(id: Module.ID, vertices: BitSet, preds: List[PipeElem], succs: List[PipeElem])

}
