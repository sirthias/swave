/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.collection.immutable.BitSet
import scala.collection.mutable
import swave.core.impl.stages.Stage
import swave.core.graph.{ GlyphSet, Digraph }
import swave.core.macros._

sealed trait PipeElem

object PipeElem {

  trait Module extends PipeElem {
    def name: String
  }

  sealed trait Basic extends PipeElem {
    def inputElems: List[PipeElem.Basic]
    def outputElems: List[PipeElem.Basic]
    def moduleEntries: List[PipeElem.Module]
    def moduleExits: List[PipeElem.Module]
    def pipeElemType: String
    def pipeElemParams: List[Any]
  }

  object Unconnected extends Basic {
    def inputElems = Nil
    def outputElems = Nil
    def moduleEntries = Nil
    def moduleExits = Nil
    def pipeElemType = "Unconnected"
    def pipeElemParams = Nil
  }

  sealed trait Source extends Basic {
    def outputElem: PipeElem.Basic
    final def inputElems = Nil
    final def outputElems: List[PipeElem.Basic] = outputElem :: Nil
  }
  object Source {
    trait Failing extends Source
    trait File extends Source
    trait FromPublisher extends Source
    trait Future extends Source
    trait Iterator extends Source
    trait Lazy extends Source
    trait Repeat extends Source
    trait Sub extends Source
    trait Test extends Source
    trait Subscriber extends Source
  }

  sealed trait Drain extends Basic {
    def inputElem: PipeElem.Basic
    final def inputElems: List[PipeElem.Basic] = inputElem :: Nil
    final def outputElems = Nil
  }
  object Drain {
    trait Cancelling extends Drain
    trait File extends Drain
    trait Foreach extends Drain
    trait FromSubscriber extends Drain
    trait Head extends Drain
    trait Lazy extends Drain
    trait Publisher extends Drain
    trait Sub extends Drain
    trait Test extends Drain
  }

  sealed trait InOut extends Basic {
    def inputElem: PipeElem.Basic
    def outputElem: PipeElem.Basic
    final def inputElems: List[PipeElem.Basic] = inputElem :: Nil
    final def outputElems: List[PipeElem.Basic] = outputElem :: Nil
  }
  object InOut {
    trait AsyncBoundary extends InOut
    trait BufferWithBackpressure extends InOut
    trait BufferDropping extends InOut
    trait Collect extends InOut
    trait Conflate extends InOut
    trait Coupling extends InOut
    trait Deduplicate extends InOut
    trait Drop extends InOut
    trait DropLast extends InOut
    trait DropWhile extends InOut
    trait DropWithin extends InOut
    trait Filter extends InOut
    trait FlattenConcat extends InOut
    trait Fold extends InOut
    trait Grouped extends InOut
    trait Inject extends InOut
    trait Limit extends InOut
    trait Map extends InOut
    trait Nop extends InOut
    trait OnEvent extends InOut
    trait OnStart extends InOut
    trait Scan extends InOut
    trait Take extends InOut
    trait Throttle extends InOut
  }

  sealed trait FanIn extends Basic {
    def outputElem: PipeElem.Basic
    final def outputElems: List[PipeElem.Basic] = outputElem :: Nil
  }
  object FanIn {
    trait Concat extends FanIn
    trait FirstNonEmpty extends FanIn
    trait ToProduct extends FanIn
  }

  sealed trait FanOut extends Basic {
    def inputElem: PipeElem.Basic
    final def inputElems: List[PipeElem.Basic] = inputElem :: Nil
  }
  object FanOut {
    trait Broadcast extends FanOut
    trait FirstAvailable extends FanOut
    trait RoundRobin extends FanOut
    trait Switch extends FanOut
  }

  def render(
    pipeElem: PipeElem.Basic,
    expandModules: List[String] = Nil,
    glyphSet: GlyphSet = GlyphSet.`3x3 ASCII`,
    showParams: Boolean = false,
    showRunners: Boolean = false,
    showNops: Boolean = false) = {

    val graph = PipeElem.assembleGraph(pipeElem, expandModules)
    if (!showNops) graph.markHidden(_.isInstanceOf[PipeElem.InOut.Nop])
    graph.render(glyphSet).format {
      case x: PipeElem.Basic ⇒
        def show =
          if (showParams || showRunners) {
            val sb = new java.lang.StringBuilder(x.pipeElemType)
            if (showParams) sb.append(x.pipeElemParams.mkString("(", ", ", ")"))
            if (showRunners) {
              val runner = x.asInstanceOf[Stage].runner
              sb.append(" ^").append(if (runner ne null) runner else "none")
            }
            sb.toString
          } else x.pipeElemType
        graph.attributes(x) match {
          case Nil     ⇒ show
          case modules ⇒ show + modules.mkString(" [", ", ", "]")
        }
      case x: PipeElem.Module ⇒ s"{${x.name}}"
    }
  }

  def assembleGraph(entryElem: PipeElem.Basic, expandModules: List[String] = Nil): Digraph[PipeElem] = {
    import Digraph.EdgeAttributes._

    ///////////////// STEP 1: discover complete graph ///////////////////////

    val graph = Digraph[PipeElem.Basic](entryElem, _.inputElems, _.outputElems)

    ///////////////// STEP 2: identify modules, apply markers //////////////////////

    val allModuleInfos: Iterable[ModuleInfo] =
      graph.vertices.toList
        .flatMap(elem ⇒ (elem.moduleEntries ::: elem.moduleExits).map(_ → elem))
        .groupBy(_._1)
        .flatMap {
          case (module, entriesAndExits) ⇒
            val entries = new mutable.ListBuffer[PipeElem.Basic]
            for ((_, elem) ← entriesAndExits)
              if (elem.moduleEntries.contains(module) && !entries.contains(elem)) entries += elem
            val exits = new mutable.ListBuffer[PipeElem.Basic]
            for ((_, elem) ← entriesAndExits)
              if (elem.moduleExits.contains(module) && !exits.contains(elem)) exits += elem
            val bitSet = graph.discoverRegion(entries, exits)
            if (bitSet.nonEmpty)
              ModuleInfo(module, bitSet, entries.flatMap(_.inputElems).toList, exits.flatMap(_.outputElems).toList) :: Nil
            else Nil
        }

    ////////////// STEP 3: filter first-level collapsed modules ////////////

    val visibleCollapsed = new mutable.ArrayBuffer[ModuleInfo]
    for (info ← allModuleInfos) {
      if (!expandModules.contains(info.module.name)) {
        visibleCollapsed.indexWhere(m ⇒ (m.vertices & info.vertices).nonEmpty) match {
          case -1 ⇒ visibleCollapsed += info
          case ix ⇒
            val alreadyStored = visibleCollapsed(ix)
            def containsAll(a: ModuleInfo, b: ModuleInfo) = (b.vertices &~ a.vertices).isEmpty // true if a contains all of b
            if (containsAll(info, alreadyStored)) visibleCollapsed(ix) = info
            else requireArg(
              containsAll(alreadyStored, info),
              s"Modules [${alreadyStored.module.name}] and [${info.module.name}] overlap without one fully containing" +
                "the other, which is unsupported for rendering!")
        }
      }
    }

    ////////////// STEP 4: construct new graph with the collapsed modules ////////////

    def collapsed(elem: PipeElem.Basic): Option[PipeElem.Module] =
      visibleCollapsed collectFirst { case info if graph.isMarked(elem, info.vertices) ⇒ info.module }

    visibleCollapsed.headOption match {
      case Some(collapsedModuleInfo) ⇒
        implicit val ordering = graph.xRankOrdering
        var vertexPreds = Map.empty[PipeElem, List[PipeElem]]
        var vertexSuccs = Map.empty[PipeElem, List[PipeElem]]
        val modulePreds = visibleCollapsed.foldLeft(Map.empty[PipeElem.Module, List[PipeElem]]) {
          case (map, ModuleInfo(module, _, mpreds, _)) ⇒
            val someModule = Some(module)
            val preds = mpreds map { p ⇒
              collapsed(p).getOrElse {
                val pSuccs = p.outputElems.map(s ⇒ if (collapsed(s) == someModule) module else s)
                vertexSuccs = vertexSuccs.updated(p, pSuccs)
                p
              } → p
            }
            map.updated(module, preds.sortBy(_._2).map(_._1))
        }
        val moduleSuccs = visibleCollapsed.foldLeft(Map.empty[PipeElem.Module, List[PipeElem]]) {
          case (map, ModuleInfo(module, _, _, msuccs)) ⇒
            val someModule = Some(module)
            val succs = msuccs map { s ⇒
              collapsed(s).getOrElse {
                val sPreds = s.inputElems.map(p ⇒ if (collapsed(p) == someModule) module else p)
                vertexPreds = vertexPreds.updated(s, sPreds)
                s
              } → s
            }
            map.updated(module, succs.sortBy(_._2).map(_._1))
        }

        def preds: PipeElem ⇒ Seq[PipeElem] = {
          case x: PipeElem.Module ⇒ modulePreds(x)
          case x: PipeElem.Basic  ⇒ vertexPreds.getOrElse(x, x.inputElems)
        }
        def succs: PipeElem ⇒ Seq[PipeElem] = {
          case x: PipeElem.Module ⇒ moduleSuccs(x)
          case x: PipeElem.Basic  ⇒ vertexSuccs.getOrElse(x, x.outputElems)
        }

        val collapsedGraph = Digraph[PipeElem](collapsedModuleInfo.module, preds, succs)
        for (info ← allModuleInfos) {
          collapsedGraph.addVertexAttributes(collapsedGraph.migrateVertexSet(graph, info.vertices), info.module.name)
        }
        collapsedGraph.vertices.foreach {
          case x: PipeElem.InOut.Coupling ⇒ collapsedGraph.markPaths(preds(x).head, succs(x).head, Reversaphile | Fusable)
          case _                          ⇒
        }
        collapsedGraph

      case None ⇒ // all modules are expanded
        for (info ← allModuleInfos) graph.addVertexAttributes(info.vertices, info.module.name)
        graph.vertices.foreach {
          case x: PipeElem.InOut.Coupling ⇒ graph.markPaths(x.inputElem, x.outputElem, Reversaphile | Fusable)
          case _                          ⇒
        }
        graph.asInstanceOf[Digraph[PipeElem]]
    }
  }

  private case class ModuleInfo(module: PipeElem.Module, vertices: BitSet,
    preds: List[PipeElem.Basic], succs: List[PipeElem.Basic])
}
