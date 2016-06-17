/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.annotation.unchecked.{ uncheckedVariance ⇒ uV }
import scala.annotation.tailrec
import shapeless.ops.product.ToHList
import shapeless.ops.hlist.Prepend
import shapeless._
import swave.core.impl.{ ModuleMarker, InportList }
import swave.core.impl.TypeLogic._
import swave.core.impl.stages.inout.NopStage
import swave.core.util._

/**
 *  A Streaming Component with four interfaces:
 *
 *  1. Incoming streams:
 *     - On the top: IT
 *     - On the bottom: IB
 *
 *  2. Outgoing streams:
 *     - On the top: OT
 *     - On the bottom: OB
 *
 *       IT    OT
 *    ┌──▼──┬──▲──┐
 *    │     │     │
 *    │     │     │
 *    └──▼──┴──▲──┘
 *       OB    IB
 */
sealed abstract class Module[-IT <: HList, +OB <: HList, -IB <: HList, +OT <: HList, +Res] private[core] (
    lit: Int, // length(IT)
    lob: Int, // length(OB)
    lib: Int, // length(IB)
    lot: Int /* length(OT) */ ) { self ⇒
  requireArg(lit + lob + lib + lot > 0, "A Module must have at least one input or output port.")

  /**
   * @param inports the streams of IT ::: IB
   * @return the streams of OB ::: OT as an InportList or the cap result of type `Res`
   */
  private[core] def apply(inports: InportList): Any

  /**
   * Returns a new anonymous [[Module]] consisting of this one and the given one
   * whereby the bottom interfaces of this module are connected to the top interfaces of the given one.
   */
  final def atop[X >: OB <: HList, OB2 <: HList, IB2 <: HList, Y <: IB, R](
    other: Module[X, OB2, IB2, Y, R])(implicit ib2Len: HLen[IB2], ob2Len: HLen[OB2], ev: Res @uV =:= Unit): Module[IT, OB2, IB2, OT, R] =
    new Module(lit, ob2Len.value, ib2Len.value, lot) {
      def apply(inports: InportList) = {
        var it = inports
        val ib2 = if (lit == 0) { it = InportList.empty; inports } else inports.drop(lit)
        val ib = nops(lib)
        val selfOuts = self(it append ib).asInstanceOf[InportList]

        var ob = selfOuts
        val ot = if (self.lob == 0) { ob = InportList.empty; selfOuts } else selfOuts.drop(self.lob)
        val otherOuts = other(ob append ib2)

        otherOuts match {
          case x: InportList ⇒
            var ob2 = x
            connect(ib, if (lob == 0) { ob2 = InportList.empty; x } else x.drop(lob))
            ob2 append ot
          case result ⇒ result
        }
      }
    }

  /**
   * Returns a new anonymous [[Module]] which performs the same logic as this one
   * but with all forward/backward directions flipped.
   * The new [[Module]] carries the same name as this instance.
   */
  final def flip: Module[IB, OT, IT, OB, Res] =
    new Module(lib, lot, lit, lob) {
      def apply(inports: InportList) = {
        var it = inports
        val ib = if (lit == 0) { it = InportList.empty; inports } else inports.drop(lit)
        self(ib append it) match {
          case outs: InportList ⇒
            var ob = outs
            val ot = if (lob == 0) {
              ob = InportList.empty; outs
            } else outs.drop(lob)
            ot append ob
          case result ⇒ result
        }
      }
    }

  /**
   * Combines this instance with the given one in a way that cross-connects all
   * inputs and outputs and thus produces a [[Piping]].
   */
  final def crossJoin[R](other: Module[OB, IT, OT, IB, R])(implicit ev: SelectNonUnit[Res @uV, R]): Piping[ev.Out] = {
    val ins = nops(lit + lib)
    var result: Any = ()
    val selfOuts = self(ins) match {
      case x: InportList ⇒ x
      case res           ⇒ { result = res; InportList.empty }
    }
    var ob = selfOuts
    val ot = if (lob == 0) { ob = InportList.empty; selfOuts } else selfOuts.drop(lob)
    other(ot append ob) match {
      case x: InportList ⇒ connect(ins, x)
      case res           ⇒ result = res
    }
    val entryElem = if (ins.nonEmpty) ins.in else selfOuts.in
    new Piping(entryElem, result).asInstanceOf[Piping[ev.Out]]
  }

  /**
   * Returns a copy of this [[Module]] with the name changed to the given one.
   */
  final def named(name: String): Module[IT, OB, IB, OT, Res] =
    new Module(lit, lob, lib, lot) {
      private val marker = new ModuleMarker(name)
      def apply(inports: InportList) = {
        val nops = inports.flatMap { ins ⇒
          val nop = new NopStage
          ins.in.subscribe()(nop)
          marker.markEntry(nop)
          InportList(nop)
        }
        val outs = self(nops)
        outs match {
          case list: InportList ⇒ list.foreach(ins ⇒ marker.markExit(ins.in))
          case _                ⇒ // nothing to do
        }
        outs
      }
    }

  @tailrec private def nops(count: Int, result: InportList = InportList.empty): InportList =
    if (count > 0) nops(count - 1, new NopStage +: result) else result

  @tailrec private def connect(nops: InportList, outs: InportList): Unit =
    if (nops.nonEmpty) {
      outs.in.subscribe()(nops.in.asInstanceOf[NopStage])
      connect(nops.tail, outs.tail)
    }
}

object Module {

  def fromStream[T](stream: Stream[T]): Forward[HNil, T :: HNil] =
    new Module(0, 1, 0, 0) {
      def apply(inports: InportList) = InportList(stream.inport)
    }

  def fromPipe[A, B](pipe: A =>> B): Forward[A :: HNil, B :: HNil] =
    new Module(1, 1, 0, 0) {
      def apply(inports: InportList) = InportList(pipe.transform(new Stream(inports.in)).inport)
    }

  def fromDrain[T, R](drain: Drain[T, R]): ForwardCap[T :: HNil, R] =
    new Module(1, 0, 0, 0) {
      def apply(inports: InportList) = drain.consume(new Stream(inports.in))
    }

  def apply[IT <: HList, OB <: HList, IB <: HList, OT <: HList](implicit c: Creator[IT, OB, IB, OT]) = c

  type Forward[I <: HList, O <: HList] = Module[I, O, HNil, HNil, Unit]
  type ForwardCap[I <: HList, Res] = Module[I, HNil, HNil, HNil, Res]

  object Forward {
    def apply[I <: HList, O <: HList](implicit c: Creator[I, O, HNil, HNil]) = c

    object from0 {
      def apply[CR, O <: HList, R](f: ⇒ CR)(implicit ev: CR ⇒ Creation[O, R], l: HLen[O]): Module[HNil, O, HNil, HNil, R] =
        Module.from(0, l.value, 0, 0, ins ⇒ ev(f))
    }

    def from1[A]: From1[A] = new From1
    final class From1[A] private[Forward] {
      def apply[CR, O <: HList, R](f: Stream[A] ⇒ CR)(implicit ev: CR ⇒ Creation[O, R], l: HLen[O]): Module[A :: HNil, O, HNil, HNil, R] =
        Module.from(1, l.value, 0, 0, ins ⇒ ev(f(new Stream(ins.in))))
    }

    def from2[A, B]: From2[A, B] = new From2
    final class From2[A, B] private[Forward] {
      def apply[CR, O <: HList, R](f: (Stream[A], Stream[B]) ⇒ CR)(implicit ev: CR ⇒ Creation[O, R], l: HLen[O]): Module[A :: B :: HNil, O, HNil, HNil, R] =
        Module.from(2, l.value, 0, 0, ins ⇒ ev(f(new Stream(ins.in), new Stream(ins.tail.in))))
    }

    def from3[A, B, C]: From3[A, B, C] = new From3
    final class From3[A, B, C] private[Forward] {
      def apply[CR, O <: HList, R](f: (Stream[A], Stream[B], Stream[CR]) ⇒ CR)(implicit ev: CR ⇒ Creation[O, R], l: HLen[O]): Module[A :: B :: CR :: HNil, O, HNil, HNil, R] =
        Module.from(3, l.value, 0, 0, ins ⇒ ev(f(new Stream(ins.in), new Stream(ins.tail.in), new Stream(ins.tail.tail.in))))
    }

    def from4[A, B, C, D]: From4[A, B, C, D] = new From4
    final class From4[A, B, C, D] private[Forward] {
      def apply[CR, O <: HList, R](f: (Stream[A], Stream[B], Stream[CR], Stream[D]) ⇒ CR)(implicit ev: CR ⇒ Creation[O, R], l: HLen[O]): Module[A :: B :: CR :: D :: HNil, O, HNil, HNil, R] =
        Module.from(4, l.value, 0, 0, ins ⇒ ev(f(new Stream(ins.in), new Stream(ins.tail.in), new Stream(ins.tail.tail.in), new Stream(ins.tail.tail.tail.in))))
    }
  }

  type Backward[I <: HList, O <: HList] = Module[HNil, HNil, I, O, Unit]

  object Backward {
    def apply[I <: HList, O <: HList](implicit c: Creator[HNil, HNil, I, O]) = c

    // TODO: add more convenience constructors in analogy to `Forward`
  }

  type Bidi[IT, OB, IB, OT] = Module[IT :: HNil, OB :: HNil, IB :: HNil, OT :: HNil, Unit]

  object Bidi {
    def apply[IT, OB, IB, OT](f: (Stream[IT], Stream[IB]) ⇒ (Stream[OB], Stream[OT])): Bidi[IT, OB, IB, OT] =
      new Module(1, 1, 1, 1) {
        def apply(inports: InportList) = {
          val (ob, ot) = f(new Stream(inports.in), new Stream(inports.tail.in))
          ob.inport +: ot.inport +: InportList.empty
        }
      }

    // TODO: add more convenience constructors in analogy to `Forward`
  }

  sealed abstract class Creator[IT <: HList, OB <: HList, IB <: HList, OT <: HList] private[Module] (
      lit: Int, lib: Int, lot: Int, lob: Int) {
    type In <: HList
    type Out <: HList
    type InUnified

    final def from[O <: HList, Res](f: In ⇒ Creation[O, Res])(implicit ev: O <:< Out): Module[IT, IB, OT, OB, Res] =
      Module.from(lit, lob, lib, lot, f.compose[InportList](_.reverse.toReversedStreamHList.asInstanceOf[In]))

    final def fromBranchOut[O <: HList, Res](f: StreamOps.BranchOut[In, HNil, CNil, InUnified, Stream] ⇒ Creation[O, Res])(implicit ev: O <:< Out): Module[IT, IB, OT, OB, Res] =
      Module.from(lit, lob, lib, lot, f.compose[InportList](ins ⇒ new StreamOps.BranchOut(ins, InportList.empty, new Stream(_))))
  }

  object Creator {
    implicit def apply[IT <: HList, OB <: HList, IB <: HList, OT <: HList, ITM <: HList, OBM <: HList, IBM <: HList, OTM <: HList, I <: HList, O <: HList](
      implicit
      a: Mapped.Aux[IT, Stream, ITM], b: Mapped.Aux[OB, Stream, OBM], c: Mapped.Aux[IB, Stream, IBM], d: Mapped.Aux[OT, Stream, OTM],
      e: Prepend.Aux[ITM, IBM, I], f: Prepend.Aux[OTM, OBM, O], lit: HLen[IT], lob: HLen[OB], lib: HLen[IB], lot: HLen[OT],
      iu: HLub[I]) =
      new Creator[IT, OB, IB, OT](lit.value, lob.value, lib.value, lot.value) {
        type In = I
        type Out = O
        type InUnified = iu.Out
      }
  }

  final class Joined[-I <: HList, +O <: HList, +Res] private (private[core] val module: Module[_, _, _, _, _]) extends AnyVal
  object Joined {
    implicit def apply[IT <: HList, OB <: HList, IB <: HList, OT <: HList, I <: HList, O <: HList, R](
      module: Module[IT, OB, IB, OT, R])(implicit e: Prepend.Aux[IT, IB, I], f: Prepend.Aux[OB, OT, O]): Joined[I, O, R] =
      new Joined(module)
  }

  def Result[T](result: T): Result[T] = new Result(result)
  final class Result[T](val result: T) extends AnyVal

  final class Creation[+O <: HList, Res] private (val out: Any)
  object Creation {
    private val empty = new Creation[HNil, Unit](())
    implicit def fromUnit(unit: Unit): Creation[HNil, Unit] = empty
    implicit def fromResult[T](res: Result[T]): Creation[HNil, T] =
      new Creation(res.result)
    implicit def fromStream[T](stream: Stream[T]): Creation[T :: HNil, Unit] =
      new Creation(InportList(stream.inport))
    implicit def fromHList[O <: HList](hlist: O): Creation[O, Unit] =
      new Creation(InportList fromHList hlist)
    implicit def fromProduct[P <: Product, O <: HList](product: P)(implicit ev: ToHList[P]): Creation[ev.Out, Unit] =
      new Creation(InportList fromHList ev(product))
    implicit def fromFanIn[O <: HList](fanIn: StreamOps.FanIn[O, _, _, Stream]): Creation[O, Unit] =
      new Creation(fanIn.subs)
  }

  private def from[IT <: HList, OB <: HList, IB <: HList, OT <: HList, R](lit: Int, lob: Int, lib: Int, lot: Int,
    f: InportList ⇒ Creation[_, _]) =
    new Module[IT, IB, OT, OB, R](lit, lob, lib, lot) {
      def apply(inports: InportList) = f(inports).out
    }
}
