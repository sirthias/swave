/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.graph

import swave.core.macros._

object GraphBuilding {
  import Dsl._
  import DslImpl._

  def input(text: String): Input          = new InputImpl(new NodeImpl(text))
  def fanOut(text: String): FanOut[Input] = new FanOutImpl[Input](new NodeImpl(text), Nil, new InputImpl(_))
  def coupling(text: String): Coupling    = new CouplingImpl(text)

  object Dsl {
    sealed abstract class Node {
      def value: String
    }

    sealed trait Coupling {
      def value: String
      def input: Input
    }

    sealed abstract class Ops { self ⇒
      type To
      type Repr <: Ops {
        type To <: self.To
        type Repr <: self.Repr
      }

      final def next(text: String): Repr = append(new NodeImpl(text))
      def attach(other: Input): FanIn[Repr]
      def attachLeft(other: Input): FanIn[Repr]
      def fanOut(fanOut: String): FanOut[Repr]
      def to(coupling: Coupling): To
      def to(drain: String): To

      protected def append(next: NodeImpl): Repr
    }

    sealed abstract class Input extends Ops {
      type To   = Digraph[Node]
      type Repr = Input
      def toDigraph: To
    }

    sealed abstract class FanIn[Repr] {
      def attach(other: Input): FanIn[Repr]
      def fanIn(text: String): Repr
      def fanInAndOut(text: String): FanOut[Repr]
    }

    sealed abstract class FanOut[FRepr] extends FanIn[FRepr] {
      def attach(other: Input): FanOut[FRepr]
      def sub: SubOps
      def subContinue: FRepr
      def continue: FRepr
      def subDrains(drains: String*): this.type

      sealed abstract class SubOps extends Ops {
        type To   = FanOut.this.type
        type Repr = SubOps
        def end: FanOut[FRepr]
      }
    }
  }

  //////////////////////////////// IMPLEMENTATION ////////////////////////////////////////

  private object DslImpl {
    import Dsl._

    class NodeImpl(val value: String) extends Node {
      var inputs: List[NodeImpl]  = Nil
      var outputs: List[NodeImpl] = Nil
      def addOutput(other: NodeImpl): Unit = {
        other.inputs :+= this
        this.outputs :+= other
      }
      override def toString: String = s"Node($value)"
    }

    final class CouplingImpl(_value: String) extends NodeImpl(_value) with Coupling {
      val input: Input = GraphBuilding.input(value)
      addOutput(impl(input).node)
      override def toString: String = s"Coupling($value)"
    }

    final class InputImpl(val node: NodeImpl) extends Input {
      protected def append(next: NodeImpl): Repr = {
        node.addOutput(next)
        new InputImpl(next)
      }
      def attach(other: Input)     = new FanInImpl[Input](node :: impl(other).node :: Nil, new InputImpl(_))
      def attachLeft(other: Input) = new FanInImpl[Input](impl(other).node :: node :: Nil, new InputImpl(_))
      def fanOut(fanOut: String)   = new FanOutImpl[Input](impl(next(fanOut)).node, Nil, new InputImpl(_))
      def to(drain: String)        = next(drain).toDigraph
      def to(coupling: Coupling)   = append(coupling.asInstanceOf[CouplingImpl]).toDigraph
      def toDigraph = {
        import Digraph.EdgeAttributes._
        val graph = Digraph[Node](node :: Nil, _.asInstanceOf[NodeImpl].inputs, _.asInstanceOf[NodeImpl].outputs)
        graph.vertices.foreach {
          case x: CouplingImpl ⇒ graph.markPaths(x, impl(x.input).node, Reversaphile | Fusable)
          case _               ⇒ // nothing to do
        }
        graph
      }
    }

    final class FanInImpl[Repr](inputs: List[NodeImpl], wrap: NodeImpl ⇒ Repr) extends FanIn[Repr] {
      def attach(other: Input) = new FanInImpl[Repr](inputs :+ impl(other).node, wrap)
      def fanIn(text: String): Repr = {
        val node = new NodeImpl(text)
        inputs.foreach(_.addOutput(node))
        wrap(node)
      }
      def fanInAndOut(text: String): FanOut[Repr] = {
        val node = new NodeImpl(text)
        inputs.foreach(_.addOutput(node))
        new FanOutImpl[Repr](node, Nil, wrap)
      }
    }

    final class FanOutImpl[FRepr](base: NodeImpl, inputs: List[NodeImpl], wrap: NodeImpl ⇒ FRepr)
        extends FanOut[FRepr] {
      def attach(other: Input) = new FanOutImpl(base, inputs :+ impl(other).node, wrap)
      def fanIn(text: String): FRepr = {
        val node = new NodeImpl(text)
        inputs.foreach(_.addOutput(node))
        wrap(node)
      }
      def fanInAndOut(text: String): FanOut[FRepr] = {
        val node = new NodeImpl(text)
        inputs.foreach(_.addOutput(node))
        new FanOutImpl[FRepr](node, Nil, wrap)
      }
      def sub: SubOps = new SubOpsImpl(base)
      def subContinue: FRepr = {
        requireArg(inputs eq Nil, "Cannot `subContinue` when other sub-streams are open")
        wrap(base)
      }
      def continue: FRepr = {
        requireArg(inputs.size == 1, ", which means the `continue` call is illegal here")
        wrap(inputs.head)
      }
      def subDrains(drains: String*): this.type = {
        drains.foreach(s ⇒ base.addOutput(new NodeImpl(s)))
        this
      }

      class SubOpsImpl(private val node: NodeImpl) extends SubOps {
        protected def append(next: NodeImpl): SubOps = {
          node.addOutput(next)
          new SubOpsImpl(next)
        }
        def attach(other: Input)     = new FanInImpl[SubOps](node :: impl(other).node :: Nil, new SubOpsImpl(_))
        def attachLeft(other: Input) = new FanInImpl[SubOps](impl(other).node :: node :: Nil, new SubOpsImpl(_))
        def fanOut(fanOut: String) =
          new FanOutImpl[SubOps](next(fanOut).asInstanceOf[SubOpsImpl].node, Nil, new SubOpsImpl(_))
        def to(drain: String): To = {
          next(drain)
          FanOutImpl.this
        }
        def to(coupling: Coupling): To = {
          append(coupling.asInstanceOf[CouplingImpl])
          FanOutImpl.this
        }
        def end: FanOut[FRepr] = {
          val n =
            if (node == base) {
              new NodeImpl("<dummy>") {
                base.addOutput(this)
                override def addOutput(other: NodeImpl): Unit = {
                  other.inputs :+= base
                  base.outputs = base.outputs.map(n ⇒ if (n eq this) other else n)
                  this.outputs :+= other
                }
              }
            } else node
          new FanOutImpl(base, inputs :+ n, wrap)
        }
      }
    }

    private def impl(input: Input): InputImpl = input.asInstanceOf[InputImpl]
  }
}
