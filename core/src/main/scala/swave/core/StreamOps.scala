/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.annotation.{ tailrec, implicitNotFound }
import scala.collection.generic.CanBuildFrom
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.Try
import shapeless._
import shapeless.ops.nat.ToInt
import shapeless.ops.hlist.{ ToCoproduct, Tupler, Fill }
import swave.core.impl.stages.Stage
import swave.core.impl.util.InportList
import swave.core.impl.stages.fanin.{ MergeStage, ToProductStage, FirstNonEmptyStage, ConcatStage }
import swave.core.impl.stages.fanout.{ RoundRobinStage, FirstAvailableStage, BroadcastStage, SwitchStage }
import swave.core.impl.{ ModuleImpl, TypeLogic, Inport }
import swave.core.impl.stages.inout._
import swave.core.util._
import swave.core.macros._
import TypeLogic._

trait StreamOps[A] extends Any { self ⇒
  import StreamOps._

  type Repr[T] <: StreamOps[T] { type Repr[X] <: self.Repr[X] }

  protected def base: Inport
  protected def wrap: Inport ⇒ Repr[_]
  protected def append[T](stage: Stage): Repr[T]

  final def async(dispatcherId: String = "", bufferSize: Int = 16): Repr[A] =
    append(new AsyncBoundaryStage(dispatcherId)).buffer(bufferSize, Overflow.Backpressure)

  final def attach[T, O](sub: Spout[T])(implicit ev: Lub[A, T, O]): FanIn[A :: T :: HNil, A :+: T :+: CNil, O, Repr] =
    new FanIn(InportList(base) :+ sub.inport, wrap)

  final def attach[L <: HList](branchOut: BranchOut[L, _, _, _, Spout])(implicit
    u: HLub[A :: L],
    tc: ToCoproduct[L]): FanIn[A :: L, A :+: tc.Out, u.Out, Repr] = new FanIn(base +: branchOut.subs, wrap)

  final def attach[L <: HList, C <: Coproduct, FO, O](fanIn: FanIn[L, C, FO, Spout])(implicit ev: Lub[A, FO, O]): FanIn[A :: L, A :+: C, O, Repr] =
    new FanIn(base +: fanIn.subs, wrap)

  final def attachAll[S, Sup >: A](subs: Traversable[S])(implicit ev: Streamable.Aux[S, Sup]): FanIn0[Sup, Repr] = {
    requireArg(subs.nonEmpty)
    new FanIn0(InportList(base) :++ subs, wrap)
  }

  final def attachLeft[T, O](sub: Spout[T])(implicit ev: Lub[A, T, O]): FanIn[T :: A :: HNil, T :+: A :+: CNil, O, Repr] =
    new FanIn(base +: InportList(sub.inport), wrap)

  final def attachN[T, O](n: Nat, fo: FanOut[T, _, _, _, Spout])(implicit f: Fill[n.N, T], ti: ToInt[n.N], lub: Lub[A, T, O]): FanIn[A :: f.Out, CNil, O, Repr] =
    new FanIn(base +: InportList.fill(ti(), attachNop(fo.base)), wrap)

  final def buffer(size: Int, overflowStrategy: Overflow = Overflow.Backpressure): Repr[A] = {
    requireArg(size >= 0)
    if (size > 0) append(overflowStrategy.newStage(size)) else identity
  }

  final def collect[B](pf: PartialFunction[A, B]): Repr[B] =
    append(new CollectStage(pf.asInstanceOf[PartialFunction[AnyRef, AnyRef]]))

  final def concat[B >: A](other: Spout[B]): Repr[B] =
    attach(other).fanInConcat

  final def conflateWithSeed[B](lift: A ⇒ B)(aggregate: (B, A) ⇒ B): Repr[B] =
    append(new ConflateStage(lift.asInstanceOf[AnyRef ⇒ AnyRef], aggregate.asInstanceOf[(AnyRef, AnyRef) ⇒ AnyRef]))

  final def conflate[B >: A](aggregate: (B, A) ⇒ B): Repr[B] =
    conflateWithSeed[B](identityFunc)(aggregate)

  final def deduplicate: Repr[A] =
    append(new DeduplicateStage)

  final def drop(n: Long): Repr[A] = {
    requireArg(n >= 0)
    if (n > 0) append(new DropStage(n)) else identity
  }

  final def dropLast(n: Int): Repr[A] = {
    requireArg(n >= 0)
    if (n > 0) append(new DropLastStage(n)) else identity
  }

  final def dropWhile(predicate: A ⇒ Boolean): Repr[A] =
    append(new DropWhileStage(predicate.asInstanceOf[Any ⇒ Boolean]))

  final def dropWithin(d: FiniteDuration): Repr[A] =
    append(new DropWithinStage(d))

  final def duplicate: Repr[A] =
    via(Pipe[A].map(x ⇒ x :: x :: Nil).flattenConcat() named "duplicate")

  final def elementAt(index: Long): Repr[A] =
    via(Pipe[A] drop index take 1 named "elementAt")

  final def expand(): Repr[A] =
    expand(Iterator.continually(_))

  final def expand[B](extrapolate: A ⇒ Iterator[B]): Repr[B] =
    expand(Iterator.empty, extrapolate)

  final def expand[B](zero: Iterator[B], extrapolate: A ⇒ Iterator[B]): Repr[B] =
    append(new ExpandStage(zero.asInstanceOf[Iterator[AnyRef]], extrapolate.asInstanceOf[AnyRef ⇒ Iterator[AnyRef]]))

  final def fanOutBroadcast(eagerCancel: Boolean = false): FanOut[A, HNil, CNil, Nothing, Repr] =
    new FanOut(append(new BroadcastStage(eagerCancel)).base, InportList.empty, wrap)

  final def fanOutFirstAvailable(eagerCancel: Boolean = false): FanOut[A, HNil, CNil, Nothing, Repr] =
    new FanOut(append(new FirstAvailableStage(eagerCancel)).base, InportList.empty, wrap)

  final def fanOutRoundRobin(eagerCancel: Boolean = false): FanOut[A, HNil, CNil, Nothing, Repr] =
    new FanOut(append(new RoundRobinStage(eagerCancel)).base, InportList.empty, wrap)

  final def filter(predicate: A ⇒ Boolean): Repr[A] =
    append(new FilterStage(predicate.asInstanceOf[Any ⇒ Boolean], negated = false))

  final def filterNot(predicate: A ⇒ Boolean): Repr[A] =
    append(new FilterStage(predicate.asInstanceOf[Any ⇒ Boolean], negated = true))

  final def filter[T](implicit classTag: ClassTag[T]): Repr[T] =
    collect { case x: T ⇒ x }

  final def first: Repr[A] =
    via(Pipe[A] take 1 named "first")

  final def flattenConcat[B](parallelism: Int = 1, subscriptionTimeout: Duration = Duration.Undefined)(
    implicit
    ev: Streamable.Aux[A, B]): Repr[B] =
    append(new FlattenConcatStage(ev.asInstanceOf[Streamable.Aux[AnyRef, AnyRef]], parallelism, subscriptionTimeout))

  final def flattenMerge[B](parallelism: Int, subscriptionTimeout: Duration = Duration.Undefined)(
    implicit
    ev: Streamable.Aux[A, B]): Repr[B] =
    append(new FlattenMergeStage(ev.asInstanceOf[Streamable.Aux[AnyRef, AnyRef]], parallelism, subscriptionTimeout))

  final def fold[B](zero: B)(f: (B, A) ⇒ B): Repr[B] =
    append(new FoldStage(zero.asInstanceOf[AnyRef], f.asInstanceOf[(AnyRef, AnyRef) ⇒ AnyRef]))

  final def groupBy[K](maxSubstreams: Int, f: A ⇒ K): Repr[Spout[A]] = ???

  final def grouped(groupSize: Int, emitSingleEmpty: Boolean = false): Repr[immutable.Seq[A]] =
    groupedTo[immutable.Seq](groupSize, emitSingleEmpty)

  final def groupedTo[M[+_]](groupSize: Int, emitSingleEmpty: Boolean = false)(implicit cbf: CanBuildFrom[M[A], A, M[A]]): Repr[M[A]] =
    append(new GroupedStage(groupSize, emitSingleEmpty, cbf.apply().asInstanceOf[scala.collection.mutable.Builder[Any, AnyRef]]))

  final def groupedWithin(n: Int, d: FiniteDuration): Repr[immutable.Seq[A]] = ???

  def identity: Repr[A]

  final def ignoreElements: Repr[A] =
    filter(_ ⇒ false)

  final def inject(subscriptionTimeout: Duration = Duration.Undefined): Repr[Spout[A]] =
    append(new InjectStage(subscriptionTimeout))

  final def interleave[B >: A](other: Spout[B], segmentSize: Int, eagerComplete: Boolean): Repr[B] =
    attach(other).fanInInterleave(segmentSize, eagerComplete)

  final def intersperse[B >: A](inject: B): Repr[B] = ???

  final def intersperse[B >: A](start: B, inject: B, end: B): Repr[B] = ???

  final def last: Repr[A] =
    takeLast(1)

  final def limit(maxElements: Long): Repr[A] =
    limitWeighted(maxElements, _ ⇒ 1)

  final def limitWeighted(max: Long, cost: A ⇒ Long): Repr[A] =
    append(new LimitStage(max, cost.asInstanceOf[AnyRef ⇒ Long]))

  final def logEvent(marker: String, log: (String, StreamEvent[A]) ⇒ Unit = defaultLogEvent): Repr[A] =
    onEvent(log(marker, _))

  final def map[B](f: A ⇒ B): Repr[B] =
    append(new MapStage(f.asInstanceOf[AnyRef ⇒ AnyRef]))

  final def mapAsync[B](parallelism: Int)(f: A ⇒ Future[B]): Repr[B] =
    map(a ⇒ () ⇒ f(a)).flattenConcat(parallelism)

  final def mapAsyncUnordered[B](parallelism: Int)(f: A ⇒ Future[B]): Repr[B] =
    map(a ⇒ () ⇒ f(a)).flattenMerge(parallelism)

  final def merge[B >: A](other: Spout[B], eagerComplete: Boolean = false): Repr[B] =
    attach(other).fanInMerge(eagerComplete)

  final def mergeSorted[B >: A: Ordering](other: Spout[B], eagerComplete: Boolean = false): Repr[B] =
    attach(other).fanInMergeSorted(eagerComplete)

  final def mergeToEither[B](other: Spout[B]): Repr[Either[A, B]] =
    map(Left[A, B])
      .attach(other.map(Right[A, B]))
      .fanInToSum[Either[A, B]]()

  final def multiply(factor: Int): Repr[A] = ???

  final def nonEmptyOr[B >: A](other: Spout[B]): Repr[B] =
    attach(other).fanInFirstNonEmpty

  final def nop: Repr[A] =
    append(new NopStage)

  final def onCancel(callback: ⇒ Unit): Repr[A] =
    onEventPF { case StreamEvent.Cancel ⇒ callback }

  final def onComplete(callback: ⇒ Unit): Repr[A] =
    onEventPF { case StreamEvent.OnComplete ⇒ callback }

  final def onElement(callback: A ⇒ Unit): Repr[A] =
    onEventPF { case StreamEvent.OnNext(element) ⇒ callback(element) }

  final def onError(callback: Throwable ⇒ Unit): Repr[A] =
    onEventPF { case StreamEvent.OnError(cause) ⇒ callback(cause) }

  final def onEvent(callback: StreamEvent[A] ⇒ Unit): Repr[A] =
    append(new OnEventStage(callback.asInstanceOf[StreamEvent[Any] ⇒ Unit]))

  final def onEventPF(callback: PartialFunction[StreamEvent[A], Unit]): Repr[A] =
    onEvent(ev ⇒ callback.applyOrElse(ev, dropFunc))

  final def onRequest(callback: Int ⇒ Unit): Repr[A] =
    onEventPF { case StreamEvent.Request(count) ⇒ callback(count) }

  final def onStart(callback: () ⇒ Unit): Repr[A] =
    append(new OnStartStage(callback))

  final def onTerminate(callback: Option[Throwable] ⇒ Unit): Repr[A] =
    onEventPF {
      case StreamEvent.OnComplete     ⇒ callback(None)
      case StreamEvent.OnError(cause) ⇒ callback(Some(cause))
    }

  final def prefixAndTail(n: Int): Repr[(immutable.Seq[A], Spout[A])] = ???

  final def recover[B >: A](pf: PartialFunction[Throwable, B]): Repr[B] = ???

  final def recoverToTry: Repr[Try[A]] = ???

  final def reduce[B >: A](f: (B, B) ⇒ B): Repr[B] = ???

  final def sample(d: FiniteDuration): Repr[A] = ???

  final def scan[B](zero: B)(f: (B, A) ⇒ B): Repr[B] =
    append(new ScanStage(zero.asInstanceOf[AnyRef], f.asInstanceOf[(AnyRef, AnyRef) ⇒ AnyRef]))

  final def slice(startIndex: Long, length: Long): Repr[A] =
    via(Pipe[A] drop startIndex take length named "slice")

  final def split(f: A ⇒ Split.Command): Repr[Spout[A]] = ???

  final def switch(n: Nat, eagerCancel: Boolean = false)(f: A ⇒ Int)(implicit ti: ToInt[n.N], fl: Fill[n.N, A]): BranchOut[fl.Out, HNil, CNil, Nothing, Repr] =
    switch[n.N](f, eagerCancel)

  final def switch[N <: Nat](f: A ⇒ Int)(implicit ti: ToInt[N], fl: Fill[N, A]): BranchOut[fl.Out, HNil, CNil, Nothing, Repr] =
    switch[N](f, eagerCancel = false)

  final def switch[N <: Nat](f: A ⇒ Int, eagerCancel: Boolean)(implicit ti: ToInt[N], fl: Fill[N, A]): BranchOut[fl.Out, HNil, CNil, Nothing, Repr] = {
    val branchCount = ti()
    val base = append(new SwitchStage(branchCount, f.asInstanceOf[AnyRef ⇒ Int], eagerCancel)).base
    new BranchOut(InportList.fill(branchCount, attachNop(base)), InportList.empty, wrap)
  }

  final def switchIf(p: A ⇒ Boolean, eagerCancel: Boolean = false): BranchOut[A :: A :: HNil, HNil, CNil, Nothing, Repr] =
    switch(2, eagerCancel)(x ⇒ if (p(x)) 1 else 0)

  final def take(count: Long): Repr[A] =
    append(new TakeStage(count))

  final def takeLast(n: Long): Repr[A] = ???

  final def takeWhile(predicate: A ⇒ Boolean): Repr[A] = ???

  final def takeWithin(d: FiniteDuration): Repr[A] = ???

  final def tee(drain: Drain[A, Unit], eagerCancel: Boolean = false): Repr[A] =
    via(Pipe[A].fanOutBroadcast(eagerCancel).sub.to(drain).subContinue named "tee")

  final def throttle(elements: Int, per: FiniteDuration, burst: Int = 1): Repr[A] =
    throttle(elements, per, burst, oneIntFunc)

  def throttle(cost: Int, per: FiniteDuration, burst: Int, costFn: A ⇒ Int): Repr[A] =
    append(new ThrottleStage(cost, per, burst, costFn.asInstanceOf[AnyRef ⇒ Int]))

  def via[B](pipe: A =>> B): Repr[B]

  final def zip[B](other: Spout[B]): Repr[(A, B)] = {
    val moduleID = Module.ID("zip")
    moduleID.markAsOuterEntry(other.inport)
    via(Pipe[A].attach(other).fanInToTuple named moduleID)
  }
}

object StreamOps {

  val defaultLogEvent: (String, StreamEvent[Any]) ⇒ Unit = { (m, ev) ⇒
    val arrow = if (ev.isInstanceOf[StreamEvent.UpEvent]) '⇠' else '⇢'
    println(s"$m: $arrow $ev")
  }

  /**
   * Homogeneous fan-in, where all fan-in streams have the same type.
   *
   * @tparam Sup  super-type of all fan-in sub-streams
   * @tparam Repr underlying representation
   */
  final class FanIn0[Sup, Repr[_]] private[StreamOps] (subs: InportList, rawWrap: Inport ⇒ Repr[_]) {

    def attach[S >: Sup](sub: Spout[S]): FanIn0[S, Repr] =
      new FanIn0(subs :+ sub.inport, rawWrap)

    def attachLeft[S >: Sup](sub: Spout[S]): FanIn0[S, Repr] =
      new FanIn0(sub.inport +: subs, rawWrap)

    def attachAll[S, Sup2 >: Sup](subs: Traversable[S])(implicit ev: Streamable.Aux[S, Sup2]): FanIn0[Sup2, Repr] = {
      requireArg(subs.nonEmpty)
      new FanIn0(this.subs :++ subs, rawWrap)
    }

    def fanInConcat: Repr[Sup] = wrap(new ConcatStage(subs))
    def fanInFirstNonEmpty: Repr[Sup] = wrap(new FirstNonEmptyStage(subs))
    def fanInInterleave(segmentSize: Int, eagerComplete: Boolean): Repr[Sup] = ???
    def fanInMerge(eagerComplete: Boolean = false): Repr[Sup] = wrap(new MergeStage(subs, eagerComplete))
    def fanInMergeSorted(eagerComplete: Boolean = false)(implicit ord: Ordering[Sup]): Repr[Sup] = ???

    private def wrap[T](in: Inport): Repr[T] = rawWrap(in).asInstanceOf[Repr[T]]
  }

  /**
   * Heterogeneous fan-in, where the fan-in streams have potentially differing types.
   *
   * @tparam L    element types of all unterminated fan-in sub-streams as an HList
   * @tparam C    element types of all unterminated fan-in sub-streams as a Coproduct
   * @tparam Sup  super-type of all unterminated fan-in sub-streams
   * @tparam Repr underlying representation
   */
  sealed class FanIn[L <: HList, C <: Coproduct, Sup, Repr[_]] private[core] (
      private[core] val subs: InportList, protected val rawWrap: Inport ⇒ Repr[_]) {

    type FI[LL <: HList, CC <: Coproduct, S] <: FanIn[LL, CC, S, Repr]

    protected def copy[LL <: HList, CC <: Coproduct, S](subs: InportList): FI[LL, CC, S] =
      new FanIn(subs, rawWrap).asInstanceOf[FI[LL, CC, S]]

    final def attach[T, Sup2, P <: HList, Q <: Coproduct](sub: Spout[T])(implicit
      ev0: Lub[Sup, T, Sup2],
      ev1: ops.hlist.Prepend.Aux[L, T :: HNil, P], ev2: ops.coproduct.Prepend.Aux[C, T :+: CNil, Q]): FI[P, Q, Sup2] =
      copy(subs :+ sub.inport)

    final def attachLeft[T, Sup2](sub: Spout[T])(implicit ev: Lub[Sup, T, Sup2]): FI[T :: L, T :+: C, Sup2] =
      copy(sub.inport +: subs)

    final def attachAll[S, Sup2 >: Sup](subs: Traversable[S])(implicit ev: Streamable.Aux[S, Sup2]): FanIn0[Sup2, Repr] = {
      requireArg(subs.nonEmpty)
      new FanIn0(this.subs :++ subs, rawWrap)
    }

    final def fanInConcat(implicit ev: FanInReq[L]): Repr[Sup] =
      wrap(new ConcatStage(subs))
    final def fanInFirstNonEmpty(implicit ev: FanInReq[L]): Repr[Sup] =
      wrap(new FirstNonEmptyStage(subs))
    final def fanInInterleave(segmentSize: Int, eagerComplete: Boolean)(implicit ev: FanInReq[L]): Repr[Sup] =
      ???
    final def fanInMerge(eagerComplete: Boolean = false)(implicit ev: FanInReq[L]): Repr[Sup] =
      wrap(new MergeStage(subs, eagerComplete))
    final def fanInMergeSorted(eagerComplete: Boolean = false)(implicit ev: FanInReq[L], ord: Ordering[Sup]): Repr[Sup] =
      ???

    final def fanInToTuple(implicit ev: FanInReq[L], t: Tuplable[L]): Repr[t.Out] =
      wrap(new ToProductStage("fanInToTuple", subs, _.toTuple))
    final def fanInToHList(implicit ev: FanInReq[L]): Repr[L] =
      wrap(new ToProductStage("fanInToHList", subs, _.toHList()))
    final def fanInToCoproduct(eagerComplete: Boolean = true)(implicit ev: FanInReq[L]): Repr[C] =
      ???
    final def fanInToProduct[T](implicit ev: FanInReq[L], gen: Productable[T, L]): Repr[T] =
      fanInToHList.asInstanceOf[StreamOps[L]].map(l ⇒ gen from l).asInstanceOf[Repr[T]]
    final def fanInToSum[T](eagerComplete: Boolean = true)(implicit ev: FanInReq[L], gen: Summable[T, C]): Repr[T] =
      fanInToCoproduct(eagerComplete).asInstanceOf[StreamOps[C]].map(c ⇒ gen from c).asInstanceOf[Repr[T]]

    def fromFanInVia[P <: HList, R, Out](joined: Module.TypeLogic.Joined[L, P, R])(
      implicit
      vr: ViaResult[P, Piping[R], Repr, Out]): Out = {
      val out = ModuleImpl(joined.module)(subs)
      val result = vr.id match {
        case 0 ⇒ new Piping(subs.in, out)
        case 1 ⇒ rawWrap(out.asInstanceOf[InportList].in)
        case 2 ⇒ new FanIn(out.asInstanceOf[InportList], rawWrap)
      }
      result.asInstanceOf[Out]
    }

    final def asBranchOut: BranchOut[L, HNil, CNil, Nothing, Repr] =
      new BranchOut(subs, InportList.empty, rawWrap)

    protected def wrap[T](in: Inport): Repr[T] = rawWrap(in).asInstanceOf[Repr[T]]
  }

  /**
   * Open fan-out, where all fan-out sub streams have the same type and there can be arbitrarily many of them.
   *
   * @tparam A    type coming in from upstream
   * @tparam L    element types of all unterminated fan-in sub-streams as an HList
   * @tparam C    element types of all unterminated fan-in sub-streams as a Coproduct
   * @tparam Sup  super-type of all unterminated fan-in sub-streams
   * @tparam Repr underlying representation
   */
  final class FanOut[A, L <: HList, C <: Coproduct, Sup, Repr[_]] private[core] (
      private[core] val base: Inport, _subs: InportList, _wrap: Inport ⇒ Repr[_]) extends FanIn[L, C, Sup, Repr](_subs, _wrap) {

    type FI[LL <: HList, CC <: Coproduct, S] = FanOut[A, LL, CC, S, Repr]

    override protected def copy[LL <: HList, CC <: Coproduct, S](subs: InportList): FI[LL, CC, S] =
      new FanOut(base, subs, rawWrap)

    def sub: SubStreamOps[A, L, C, Sup, Repr, FanOut[A, L, C, Sup, Repr]] =
      new SubStreamOps(this, new Spout(attachNop(base)))

    def subContinue(implicit ev0: SubContinueReq1[L]): Repr[A] = wrap(base)

    def continue(implicit ev0: ContinueReq1[L]): Repr[ev0.Out] = wrap(subs.in)

    def subDrains(drains: List[Drain[A, Unit]]): this.type = {
      @tailrec def rec(remaining: List[Drain[A, Unit]]): Unit =
        if (remaining.nonEmpty) {
          remaining.head.consume(new Spout(base))
          rec(remaining.tail)
        }
      rec(drains)
      this
    }
  }

  /**
   * Closed fan-out, where the number of fan-out sub streams and their (potentially differing) types are predefined.
   *
   * @tparam A    element types of the still unconsumed fan-out sub-streams as an HList
   * @tparam L    element types of all unterminated fan-in sub-streams as an HList
   * @tparam C    element types of all unterminated fan-in sub-streams as a Coproduct
   * @tparam Sup  super-type of all unterminated fan-in sub-streams
   * @tparam Repr underlying representation
   */
  final class BranchOut[A <: HList, L <: HList, C <: Coproduct, Sup, Repr[_]] private[core] (
      ins: InportList, _subs: InportList, _wrap: Inport ⇒ Repr[_]) extends FanIn[L, C, Sup, Repr](_subs, _wrap) {

    type FI[LL <: HList, CC <: Coproduct, S] = BranchOut[A, LL, CC, S, Repr]
    private type ViaBranchOut[LL <: HList, CC <: Coproduct, S] = BranchOut[LL, HNil, CNil, Nothing, Repr]

    override protected def copy[LL <: HList, CC <: Coproduct, S](subs: InportList): FI[LL, CC, S] =
      new BranchOut(ins, subs, rawWrap)

    def sub(implicit ev: SubReq[A]): SubStreamOps[ev.H, L, C, Sup, Repr, BranchOut[ev.T, L, C, Sup, Repr]] =
      new SubStreamOps(new BranchOut[ev.T, L, C, Sup, Repr](ins.tail, subs, rawWrap), new Spout(ins.in))

    def subContinue(implicit ev0: SubContinueReq0[L], ev1: SubContinueReq1[L]): Repr[A] = wrap(ins.in)

    def continue(implicit ev0: ContinueReq0[L], ev1: ContinueReq1[L]): Repr[ev1.Out] = wrap(subs.in)

    //    def fromBranchOutVia[P <: HList, R](joined: Module.Joined[A, P, R])(implicit ev: ViaContinueReq[L], vr: ViaResult[P, RunnablePiping[R], Repr]): vr.Out = {
    //      val out = joined.module(ins)
    //      val result = vr.id match {
    //        case 0 ⇒ new RunnablePiping(ins.in, out)
    //        case 1 ⇒ rawWrap(out.asInstanceOf[InportList].in)
    //        case 2 ⇒ new FanIn(out.asInstanceOf[InportList], rawWrap)
    //      }
    //      result.asInstanceOf[vr.Out]
    //    }
  }

  /**
   * The operations underneath a fan/branch-out sub.
   */
  final class SubStreamOps[A, L <: HList, C <: Coproduct, Sup, FRepr[_], F <: FanIn[L, C, Sup, FRepr]] private[core] (
      fo: F, spout: Spout[A]) extends StreamOps[A] {
    type Repr[X] = SubStreamOps[X, L, C, Sup, FRepr, F]

    protected def base: Inport = spout.inport
    protected def wrap: Inport ⇒ Repr[_] = in ⇒ new SubStreamOps(fo, new Spout(in))
    protected def append[B](stage: Stage): Repr[B] = new SubStreamOps(fo, spout.append(stage))

    def identity: Repr[A] = this

    def to[R](drain: Drain[A, Unit]): F = {
      drain.consume(spout)
      fo
    }

    def via[B](pipe: A =>> B): Repr[B] = new SubStreamOps(fo, spout via pipe)

    def via[P <: HList, R, Out](joined: Module.TypeLogic.Joined[A :: HNil, P, R])(
      implicit
      vr: TypeLogic.ViaResult[P, F, Repr, Out]): Out = {
      val out = ModuleImpl(joined.module)(InportList(spout.inport))
      val result = vr.id match {
        case 0 ⇒ fo
        case 1 ⇒ new SubStreamOps[A, L, C, Sup, FRepr, F](fo, new Spout(out.asInstanceOf[InportList].in))
        case 2 ⇒ new StreamOps.FanIn(out.asInstanceOf[InportList], wrap)
      }
      result.asInstanceOf[Out]
    }

    def end[Sup2, P <: HList, Q <: Coproduct](implicit ev0: Lub[Sup, A, Sup2], ev1: ops.hlist.Prepend.Aux[L, A :: HNil, P],
      ev2: ops.coproduct.Prepend.Aux[C, A :+: CNil, Q]): F#FI[P, Q, Sup2] = fo.attach(spout).asInstanceOf[F#FI[P, Q, Sup2]]
  }

  @implicitNotFound(msg = "Cannot fan-in here. You need to have at least two open fan-in sub-streams.")
  private type FanInReq[L <: HList] = IsHCons2[L]

  @implicitNotFound(msg = "Cannot assemble product type `${T}` from `${L}`.")
  private type Productable[T, L <: HList] = Generic.Aux[T, L]

  @implicitNotFound(msg = "Cannot assemble sum type `${T}` from `${C}`.")
  private type Summable[T, C <: Coproduct] = Generic.Aux[T, C]

  @implicitNotFound(msg = "Cannot convert `${L}` into a tuple.")
  private type Tuplable[L <: HList] = Tupler[L]

  @implicitNotFound(msg = "Illegal substream definition! All available fan-out sub-streams have already been consumed.")
  private type SubReq[L <: HList] = IsHCons[L]

  @implicitNotFound(msg = "`subContinue` is only possible with exactly one remaining fan-out sub-stream unconsumed!")
  private type SubContinueReq0[L <: HList] = IsSingle[L]
  @implicitNotFound(msg = "`subContinue` is only possible without any previous fan-in sub-streams! Here you have: ${L}.")
  private type SubContinueReq1[L <: HList] = IsHNil[L]

  @implicitNotFound(msg = "Cannot continue stream definition here! You still have at least one unconsumed fan-out sub-stream.")
  private type ContinueReq0[L <: HList] = IsHNil[L]
  @implicitNotFound(msg = "Continuation is only possible with exactly one open fan-in sub-stream!")
  private type ContinueReq1[L <: HList] = IsSingle[L]

  @implicitNotFound(msg = "`via` is only possible here without any previous fan-in sub-streams! Here you have: ${L}.")
  private type ViaContinueReq[L <: HList] = IsHNil[L]

  private def attachNop(base: Inport): Inport = {
    val stage = new NopStage
    base.subscribe()(stage)
    stage
  }
}
