/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import swave.core.impl.util.InportList

import scala.annotation.implicitNotFound
import shapeless.ops.product.ToHList
import shapeless.ops.hlist.Prepend
import shapeless._
import swave.core.impl.TypeLogic._
import swave.core.impl._

/**
  *  A Streaming Component with up to four interfaces:
  *  - A `Top` input
  *  - A `Top` output
  *  - A `Bottom` input
  *  - A `Bottom` output
  *
  *       IT    OT
  *    ┌──▼──┬──▲──┐
  *    │     │     │
  *    │     │     │
  *    └──▼──┴──▲──┘
  *       OB    IB
  *
  *  A `Module` without output interfaces might produce a result of type `R`.
  */
abstract class Module[I <: Module.Input, O <: Module.Output] private[core] {
  import Module.TypeLogic._
  import Module._

  def id: Module.ID

  /**
    * Returns a new anonymous [[Module]] consisting of this one and the given one
    * whereby the bottom interfaces of this module are connected to the top interfaces of the given one.
    */
  def atop[I2 <: Input, O2 <: Output](other: Module[I2, O2])(implicit ev: Atop[I, O, I2, O2]): Module[ev.IR, ev.OR]

  /**
    * Returns a new [[Module]] which performs the same logic as this one but with all forward/backward directions
    * flipped. The new [[Module]] carries the same name as this instance.
    */
  def flip(implicit ev: Flip[I, O]): Module[ev.IR, ev.OR]

  /**
    * Combines this instance with the given one in a way that cross-connects all
    * inputs and outputs and thus produces a [[StreamGraph]].
    */
  def crossJoin[I2 <: Input, O2 <: Output](other: Module[I2, O2])(
      implicit ev: CrossJoin[I, O, I2, O2]): StreamGraph[Unit]

  /**
    * Returns a copy of this [[Module]] with the name changed to the given one.
    */
  def named(name: String): Module[I, O]
}

object Module {
  sealed trait Input {
    type Top <: HList
    type Bottom <: HList
  }
  object Input {
    type None                         = Bidi[HNil, HNil]
    type Top1[T]                      = Top[T :: HNil]
    type Top[L <: HList]              = Bidi[L, HNil]
    type Bottom1[T]                   = Bottom[T :: HNil]
    type Bottom[L <: HList]           = Bidi[HNil, L]
    type Bidi11[T, B]                 = Bidi[T :: HNil, B :: HNil]
    type Bidi[T <: HList, B <: HList] = Input { type Top = T; type Bottom = B }
  }

  sealed trait Output {
    type Top <: HList
    type Bottom <: HList
  }
  object Output {
    type None               = Bidi[HNil, HNil]
    type Top1[T]            = Top[T :: HNil]
    type Top[L <: HList]    = Bidi[L, HNil]
    type Bottom1[T]         = Bottom[T :: HNil]
    type Bottom[L <: HList] = Bidi[HNil, L]
    type Bidi11[T, B]       = Bidi[T :: HNil, B :: HNil]
    sealed trait Bidi[T <: HList, B <: HList] extends Output {
      type Top    = T
      type Bottom = B
    }
    sealed trait Result[R] extends Output {
      type Top    = Nothing
      type Bottom = Nothing
    }

    import TypeLogic._
    def None: Creation[Output.None] =
      new Creation(())
    def Top1[T](spout: Spout[T]): Creation[Output.Top1[T]] =
      new Creation(InportList(spout.inport))
    def Top[L <: HList](hListOfSpouts: L)(implicit ev: IsHListOfSpout[L]): Creation[Output.Top[ev.Out]] =
      new Creation(InportList fromHList hListOfSpouts)
    def Bottom1[T](spout: Spout[T]): Creation[Output.Bottom1[T]] =
      new Creation(InportList(spout.inport))
    def Bottom[L <: HList](hListOfSpouts: L)(implicit ev: IsHListOfSpout[L]): Creation[Output.Bottom[ev.Out]] =
      new Creation(InportList fromHList hListOfSpouts)
    def Bidi11[T, B](topOutput: Spout[T], bottomOutput: Spout[B]): Creation[Output.Bidi11[T, B]] =
      new Creation(topOutput.inport +: bottomOutput.inport +: InportList.empty)
    def Bidi[T <: HList, B <: HList](top: T, bottom: B)(
        implicit evT: IsHListOfSpout[T],
        evB: IsHListOfSpout[B]): Creation[Output.Bidi[evT.Out, evB.Out]] =
      new Creation(InportList.fromHList(top) append InportList.fromHList(bottom))
    def Result[T](result: T): Creation[Output.Result[T]] =
      new Creation(result)
  }

  /**
    * Packages the given [[Spout]] as a [[Module]].
    */
  def fromSpout[T](spout: Spout[T]): Module[Input.None, Output.Bottom1[T]] =
    ModuleImpl(0, 0)(_ ⇒ InportList(spout.inport))

  /**
    * Packages the given [[Pipe]] as a [[Module]].
    */
  def fromPipe[A, B](pipe: Pipe[A, B]): Forward[A :: HNil, B :: HNil] =
    ModuleImpl(1, 0)(inports ⇒ InportList(pipe.transform(new Spout(inports.in)).inport))

  /**
    * Packages the given [[Drain]] as a [[Module]].
    */
  def fromDrain[T, R](drain: Drain[T, R]): Module[Input.Top1[T], Output.Result[R]] =
    ModuleImpl(1, 0)(inports ⇒ drain.consume(new Spout(inports.in)))

  def apply[I <: Input, O <: Output](implicit c: TypeLogic.Creator[I, O]) = c

  type Forward[I <: HList, O <: HList] = Module[Input.Top[I], Output.Bottom[O]]

  object Forward {
    import TypeLogic._
    def apply[I <: HList, O <: HList](implicit c: Creator[Input.Top[I], Output.Bottom[O]]) = c

    @implicitNotFound(msg = "`Forward` modules must not have a top output.")
    private type Req[O <: Output] = IsHNil[O#Top]

    object from0 {
      def apply[CR, O <: Output](f: ⇒ CR)(implicit c: CR ⇒ Creation[O], ev: Req[O]): Module[Input.None, O] =
        ModuleImpl(0, 0)(_ ⇒ c(f).out)
    }

    def from1[A]: From1[A] = new From1
    final class From1[A] private[Forward] {
      def apply[CR, O <: Output](f: Spout[A] ⇒ CR)(implicit c: CR ⇒ Creation[O],
                                                   ev: Req[O]): Module[Input.Top1[A], O] =
        ModuleImpl(1, 0)(ins ⇒ c(f(new Spout(ins.in))).out)
    }

    def from2[A, B]: From2[A, B] = new From2
    final class From2[A, B] private[Forward] {
      def apply[CR, O <: Output](f: (Spout[A], Spout[B]) ⇒ CR)(implicit c: CR ⇒ Creation[O],
                                                               ev: Req[O]): Module[Input.Top[A :: B :: HNil], O] =
        ModuleImpl(2, 0)(ins ⇒ c(f(new Spout(ins.in), new Spout(ins.tail.in))).out)
    }

    def from3[A, B, C]: From3[A, B, C] = new From3
    final class From3[A, B, C] private[Forward] {
      def apply[CR, O <: Output](f: (Spout[A], Spout[B], Spout[C]) ⇒ CR)(
          implicit c: CR ⇒ Creation[O],
          ev: Req[O]): Module[Input.Top[A :: B :: C :: HNil], O] =
        ModuleImpl(3, 0)(ins ⇒ c(f(new Spout(ins.in), new Spout(ins.tail.in), new Spout(ins.tail.tail.in))).out)
    }

    def from4[A, B, C, D]: From4[A, B, C, D] = new From4
    final class From4[A, B, C, D] private[Forward] {
      def apply[CR, O <: Output](f: (Spout[A], Spout[B], Spout[C], Spout[D]) ⇒ CR)(
          implicit c: CR ⇒ Creation[O],
          ev: Req[O]): Module[Input.Top[A :: B :: C :: D :: HNil], O] =
        ModuleImpl(4, 0)(
          ins ⇒
            c(
              f(
                new Spout(ins.in),
                new Spout(ins.tail.in),
                new Spout(ins.tail.tail.in),
                new Spout(ins.tail.tail.tail.in))).out)
    }
  }

  type SimpleBidi[IT, IB, OT, OB] = Module[Input.Bidi11[IT, IB], Output.Bidi11[OT, OB]]

  object SimpleBidi {
    def apply[IT, IB, OT, OB](f: (Spout[IT], Spout[IB]) ⇒ (Spout[OT], Spout[OB])): SimpleBidi[IT, IB, OT, OB] =
      ModuleImpl(1, 1) { inports ⇒
        val (ot, ob) = f(new Spout(inports.in), new Spout(inports.tail.in))
        ot.inport +: ob.inport +: InportList.empty
      }
  }

  sealed abstract class Boundary(val isEntry: Boolean, val isInner: Boolean) {
    def stage: Stage
    final def isExit: Boolean  = !isEntry
    final def isOuter: Boolean = !isInner
  }

  object Boundary {
    final case class InnerEntry(stage: Stage) extends Boundary(true, true)
    final case class OuterEntry(stage: Stage) extends Boundary(true, false)
    final case class InnerExit(stage: Stage)  extends Boundary(false, true)
    final case class OuterExit(stage: Stage)  extends Boundary(false, false)
  }

  def ID(name: String = "") = new ID(name)

  final class ID private[Module] (val name: String) {
    private[this] var _boundaries  = List.empty[Boundary]
    private[this] var _sealed      = false
    def boundaries: List[Boundary] = _boundaries

    private[swave] def addBoundary(boundary: Boundary): this.type = {
      _boundaries ::= boundary
      boundary.stage.asInstanceOf[PortImpl].markAsBoundaryOf(this)
      this
    }

    private[core] def markSealed(): Boolean = !_sealed && { _sealed = true; true }

    override def toString = s"""Module.ID(name="$name", boundaries=$boundaries)"""
  }

  object TypeLogic {

    sealed trait Atop[I <: Input, O <: Output, I2 <: Input, O2 <: Output] {
      type IR <: Input
      type OR <: Output
    }

    object Atop {
      @implicitNotFound(
        msg = "Cannot assemble modules. The bottom module cannot digest the bottom output of the top module.")
      private type Req0[O <: Output, I2 <: Input] = O#Bottom <:< I2#Top
      @implicitNotFound(
        msg = "Cannot assemble modules. The top module cannot digest the top output of the bottom module.")
      private type Req1[O2 <: Output, I <: Input] = O2#Top <:< I#Bottom

      @implicitNotFound(msg = "Cannot assemble modules. The top module must not be a result-producing module.")
      sealed trait TopAndBottom[O <: Output, O2 <: Output] {
        type Out <: Output
      }

      object TopAndBottom {

        import Output._

        implicit def _0[O <: Bidi[_ <: HList, _ <: HList], O2 <: Bidi[_ <: HList, _ <: HList]]
          : TopAndBottom[O, O2] { type Out = Bidi[O#Top, O2#Bottom] } = null

        implicit def _1[O <: Bidi[_ <: HList, _ <: HList], O2 <: Result[_]]: TopAndBottom[O, O2] { type Out = O2 } =
          null
      }

      implicit def apply[I <: Input, O <: Output, I2 <: Input, O2 <: Output](
          implicit ev0: Req0[O, I2],
          ev1: Req1[O2, I],
          tab: TopAndBottom[O, O2]): Atop[I, O, I2, O2] {
        type IR = Input.Bidi[I#Top, I2#Bottom]
        type OR = tab.Out
      } = null
    }

    sealed trait Flip[I <: Input, O <: Output] {
      type IR <: Input
      type OR <: Output
    }

    object Flip {
      @implicitNotFound(msg = "Cannot flip a result-producing module.")
      private type Req[O <: Output] = O <:< Output.Bidi[_, _]

      implicit def apply[I <: Input, O <: Output](implicit ev: Req[O]): Flip[I, O] {
        type IR = Input.Bidi[I#Bottom, I#Top]
        type OR = Output.Bidi[O#Bottom, O#Top]
      } = null
    }

    sealed trait CrossJoin[I <: Input, O <: Output, I2 <: Input, O2 <: Output] {
      type Res
    }

    object CrossJoin {
      @implicitNotFound(msg =
        "Cannot crossJoin modules. The 2nd module's top input cannot digest the bottom output of the 1st module.")
      private type Req0[O <: Output, I2 <: Input] = O#Bottom <:< I2#Top
      @implicitNotFound(msg =
        "Cannot crossJoin modules. The 1st module's top input cannot digest the bottom output of the 2nd module.")
      private type Req1[O2 <: Output, I <: Input] = O2#Bottom <:< I#Top
      @implicitNotFound(msg =
        "Cannot crossJoin modules. The 1st module's bottom input cannot digest the top output of the 2nd module.")
      private type Req2[O2 <: Output, I <: Input] = O2#Top <:< I#Bottom
      @implicitNotFound(msg =
        "Cannot crossJoin modules. The 2nd module's bottom input cannot digest the top output of the 1st module.")
      private type Req3[O <: Output, I2 <: Input] = O#Top <:< I2#Bottom

      implicit def apply[I <: Input, O <: Output, I2 <: Input, O2 <: Output](
          implicit ev0: Req0[O, I2],
          ev1: Req1[O2, I],
          ev2: Req0[O2, I],
          ev3: Req1[O, I2]): CrossJoin[I, O, I2, O2] = null
    }

    sealed abstract class Creator[I <: Input, O <: Output] private (lit: Int, lot: Int) {
      type In <: HList
      type InUnified

      final def from(f: In ⇒ Creation[O]): Module[I, O] =
        ModuleImpl(lit, lot)(ins ⇒ f(ins.reverse.toReversedSpoutHList.asInstanceOf[In]).out)

      final def fromBranchOut(f: Spout[_]#BranchOut[In, HNil, InUnified] ⇒ Creation[O]): Module[I, O] =
        ModuleImpl(lit, lot) { ins ⇒
          val s = new Spout(null) // dummy
          f(new s.BranchOut(ins, InportList.empty)).out
        }
    }

    object Creator {
      implicit def apply[I <: Input, O <: Output, IT <: HList, IB <: HList, IX <: HList](
          implicit a: Mapped.Aux[I#Top, Spout, IT],
          b: Mapped.Aux[I#Bottom, Spout, IB],
          c: Prepend.Aux[IT, IB, IX],
          lit: HLen[I#Top],
          lot: HLen[O#Top],
          iu: HLub[IX]) =
        new Creator[I, O](lit.value, lot.value) {
          type In        = IX
          type InUnified = iu.Out
        }
    }

    final class Creation[+O <: Output](val out: Any) extends AnyVal

    object Creation {
      implicit def fromSpout[T](spout: Spout[T]): Creation[Output.Bottom1[T]] =
        new Creation(InportList(spout.inport))

      implicit def fromHList[L <: HList](hlist: L)(implicit ev: IsHListOfSpout[L]): Creation[Output.Bottom[ev.Out]] =
        new Creation(InportList fromHList hlist)

      implicit def fromProduct[P <: Product, L <: HList](
          product: P)(implicit ev0: ToHList.Aux[P, L], ev1: IsHListOfSpout[L]): Creation[Output.Bottom[ev1.Out]] =
        new Creation(InportList fromHList ev0(product))

      implicit def fromFanIn[L <: HList](fanIn: Spout[_]#FanIn[L, _]): Creation[Output.Bottom[L]] =
        new Creation(fanIn.subs)
    }

    final class Joined[-I <: HList, +O <: HList, +Res] private[Module] (private[core] val module: Module[_, _])
        extends AnyVal

    object Joined extends LowPriorityJoined {
      implicit def _1[I <: Input, R, O <: Output.Result[R], IJ <: HList](module: Module[I, O])(
          implicit ev: Prepend.Aux[I#Top, I#Bottom, IJ]): Joined[IJ, HNil, R] =
        new Joined(module)
    }

    sealed abstract class LowPriorityJoined {
      implicit def _0[I <: Input, O <: Output, IJ <: HList, OJ <: HList](module: Module[I, O])(
          implicit ev0: Prepend.Aux[I#Top, I#Bottom, IJ],
          ev1: Prepend.Aux[O#Top, O#Bottom, OJ]): Joined[IJ, OJ, Unit] =
        new Joined(module)
    }
  }
}
