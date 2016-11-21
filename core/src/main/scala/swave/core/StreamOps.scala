/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import scala.annotation.{implicitNotFound, tailrec}
import scala.collection.generic.CanBuildFrom
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}
import shapeless._
import shapeless.ops.nat.ToInt
import shapeless.ops.hlist.{Fill, ToCoproduct, Tupler}
import swave.core.impl.stages.StageImpl
import swave.core.impl.util.{InportList, RingBuffer}
import swave.core.impl.stages.fanin._
import swave.core.impl.stages.fanout._
import swave.core.impl.stages.flatten._
import swave.core.impl.stages.inject._
import swave.core.impl.stages.inout._
import swave.core.impl.{Inport, ModuleImpl, TypeLogic}
import swave.core.util._
import swave.core.macros._
import TypeLogic._

/**
  * Defines all the transformations that are available on [[Spout]], [[Pipe]] and fan-out sub-streams.
  */
trait StreamOps[A] extends Any { self ⇒
  import StreamOps._

  type Repr[T] <: StreamOps[T] { type Repr[X] <: self.Repr[X] }

  protected def base: Inport
  protected def wrap: Inport ⇒ Repr[_]
  protected def append[T](stage: StageImpl): Repr[T]

  final def async(dispatcherId: String = "", bufferSize: Int = 16): Repr[A] =
    append(new AsyncBoundaryStage(dispatcherId)).buffer(bufferSize)

  final def attach[T, O](sub: Spout[T])(implicit ev: Lub[A, T, O]): FanIn[A :: T :: HNil, A :+: T :+: CNil, O, Repr] =
    new FanIn(InportList(base) :+ sub.inport, wrap)

  final def attach[L <: HList](branchOut: BranchOut[L, _, _, _, Spout])(
      implicit u: HLub[A :: L],
      tc: ToCoproduct[L]): FanIn[A :: L, A :+: tc.Out, u.Out, Repr] = new FanIn(base +: branchOut.subs, wrap)

  final def attach[L <: HList, C <: Coproduct, FO, O](fanIn: FanIn[L, C, FO, Spout])(
      implicit ev: Lub[A, FO, O]): FanIn[A :: L, A :+: C, O, Repr] =
    new FanIn(base +: fanIn.subs, wrap)

  final def attachAll[S, Sup >: A](subs: Traversable[S])(implicit ev: Streamable.Aux[S, Sup]): FanIn0[Sup, Repr] = {
    requireArg(subs.nonEmpty, "Cannot `attachAll` without open sub-streams")
    new FanIn0(InportList(base) :++ subs, wrap)
  }

  final def attachLeft[T, O](sub: Spout[T])(
      implicit ev: Lub[A, T, O]): FanIn[T :: A :: HNil, T :+: A :+: CNil, O, Repr] =
    new FanIn(base +: InportList(sub.inport), wrap)

  final def attachN[T, O](n: Nat, fo: FanOut[T, _, _, _, Spout])(implicit f: Fill[n.N, T],
                                                                 ti: ToInt[n.N],
                                                                 lub: Lub[A, T, O]): FanIn[A :: f.Out, CNil, O, Repr] =
    new FanIn(base +: InportList.fill(ti(), attachNop(fo.base)), wrap)

  final def buffer(size: Int,
                   requestStrategy: Buffer.RequestStrategy = Buffer.RequestStrategy.WhenHalfEmpty): Repr[A] = {
    requireArg(size >= 0, "`size` must be >= 0")
    if (size > 0) append(new BufferStage(size, requestStrategy(size))) else identity
  }

  final def bufferDropping(size: Int, overflowStrategy: Buffer.OverflowStrategy): Repr[A] = {
    requireArg(size > 0, "`size` must be > 0")
    append(new BufferDroppingStage(size, overflowStrategy))
  }

  final def collect[B](pf: PartialFunction[A, B]): Repr[B] =
    append(new CollectStage(pf.asInstanceOf[PartialFunction[AnyRef, AnyRef]]))

  final def concat[B >: A](other: Spout[B]): Repr[B] =
    attach(other).fanInConcat()

  final def conflateWithSeed[B](lift: A ⇒ B)(aggregate: (B, A) ⇒ B): Repr[B] =
    append(new ConflateStage(lift.asInstanceOf[AnyRef ⇒ AnyRef], aggregate.asInstanceOf[(AnyRef, AnyRef) ⇒ AnyRef]))

  final def conflate[B >: A](aggregate: (B, A) ⇒ B): Repr[B] =
    conflateWithSeed[B](identityFunc)(aggregate)

  final def deduplicate: Repr[A] =
    append(new DeduplicateStage)

  final def delay(f: A ⇒ FiniteDuration): Repr[A] =
    append(new DelayStage(f.asInstanceOf[AnyRef ⇒ FiniteDuration]))

  final def drop(n: Long): Repr[A] = {
    requireArg(n >= 0, "`n` must be >= 0")
    if (n > 0) append(new DropStage(n)) else identity
  }

  final def dropAll: Repr[A] =
    via(Pipe[A] drop Long.MaxValue named "dropAll")

  final def dropLast(n: Int): Repr[A] = {
    requireArg(n >= 0, "`n` must be >= 0")
    if (n > 0) append(new DropLastStage(n)) else identity
  }

  final def dropWhile(predicate: A ⇒ Boolean): Repr[A] =
    append(new DropWhileStage(predicate.asInstanceOf[Any ⇒ Boolean]))

  final def dropWithin(d: FiniteDuration): Repr[A] =
    append(new DropWithinStage(d))

  final def duplicate: Repr[A] =
    via(Pipe[A] multiply 2 named "duplicate")

  final def elementAt(index: Long): Repr[A] =
    via(Pipe[A] drop index take 1 named "elementAt")

  final def expand(): Repr[A] =
    expand(Iterator.continually(_))

  final def expand[B](extrapolate: A ⇒ Iterator[B]): Repr[B] =
    expand(Iterator.empty, extrapolate)

  final def expand[B](zero: Iterator[B], extrapolate: A ⇒ Iterator[B]): Repr[B] =
    append(new ExpandStage(zero.asInstanceOf[Iterator[AnyRef]], extrapolate.asInstanceOf[AnyRef ⇒ Iterator[AnyRef]]))

  final def fanOutBroadcast(eagerCancel: Boolean = false): FanOut[A, HNil, CNil, Nothing, Repr] =
    new FanOut(append(new FanOutBroadcastStage(eagerCancel)).base, InportList.empty, wrap)

  final def fanOutBroadcastBuffered(bufferSize: Int,
                                    requestStrategy: Buffer.RequestStrategy = Buffer.RequestStrategy.WhenHalfEmpty,
                                    eagerCancel: Boolean = false): FanOut[A, HNil, CNil, Nothing, Repr] = {
    requireArg(bufferSize >= 0, "`bufferSize` must be >= 0")
    if (bufferSize > 0) {
      val stage = new FanOutBroadcastBufferedStage(bufferSize, requestStrategy(bufferSize), eagerCancel)
      new FanOut(append(stage).base, InportList.empty, wrap)
    } else fanOutBroadcast(eagerCancel)
  }

  final def fanOutRoundRobin(eagerCancel: Boolean = false): FanOut[A, HNil, CNil, Nothing, Repr] =
    ???

  final def fanOutSequential(eagerCancel: Boolean = false): FanOut[A, HNil, CNil, Nothing, Repr] =
    ???

  final def fanOutSwitch(n: Nat, eagerCancel: Boolean = false)(
      f: A ⇒ Int)(implicit ti: ToInt[n.N], fl: Fill[n.N, A]): BranchOut[fl.Out, HNil, CNil, Nothing, Repr] =
    fanOutSwitch[n.N](f, eagerCancel)

  final def fanOutSwitch[N <: Nat](f: A ⇒ Int)(implicit ti: ToInt[N],
                                               fl: Fill[N, A]): BranchOut[fl.Out, HNil, CNil, Nothing, Repr] =
    fanOutSwitch[N](f, eagerCancel = false)

  final def fanOutSwitch[N <: Nat](f: A ⇒ Int, eagerCancel: Boolean)(
      implicit ti: ToInt[N],
      fl: Fill[N, A]): BranchOut[fl.Out, HNil, CNil, Nothing, Repr] = {
    val branchCount = ti()
    val base        = append(new FanOutSwitchStage(branchCount, f.asInstanceOf[AnyRef ⇒ Int], eagerCancel)).base
    new BranchOut(InportList.fill(branchCount, attachNop(base)), InportList.empty, wrap)
  }

  final def fanOutToAny(eagerCancel: Boolean = false): FanOut[A, HNil, CNil, Nothing, Repr] =
    ???

  final def fanOutUnZip[L <: HList](eagerCancel: Boolean = false)(
      implicit ev: FromProduct[A, L]): BranchOut[L, HNil, CNil, Nothing, Repr] =
    ???

  final def filter(predicate: A ⇒ Boolean): Repr[A] =
    append(new FilterStage(predicate.asInstanceOf[Any ⇒ Boolean], negated = false))

  final def filterNot(predicate: A ⇒ Boolean): Repr[A] =
    append(new FilterStage(predicate.asInstanceOf[Any ⇒ Boolean], negated = true))

  final def filter[T](implicit classTag: ClassTag[T]): Repr[T] =
    collect { case x: T ⇒ x }

  final def first: Repr[A] =
    via(Pipe[A] take 1 named "first")

  final def flatMap[B, C](f: A ⇒ B, parallelism: Int = 1)(implicit ev: Streamable.Aux[B, C]): Repr[C] =
    via(Pipe[A] map f flattenConcat parallelism named "flatmap")

  final def flattenConcat[B](parallelism: Int = 1)(implicit ev: Streamable.Aux[A, B]): Repr[B] =
    append(new FlattenConcatStage(ev.asInstanceOf[Streamable.Aux[AnyRef, AnyRef]], parallelism))

  final def flattenMerge[B](parallelism: Int)(implicit ev: Streamable.Aux[A, B]): Repr[B] =
    append(new FlattenMergeStage(ev.asInstanceOf[Streamable.Aux[AnyRef, AnyRef]], parallelism))

  final def flattenRoundRobin[B](parallelism: Int)(implicit ev: Streamable.Aux[A, B]): Repr[B] =
    ???

  final def flattenSorted[B: Ordering](parallelism: Int)(implicit ev: Streamable.Aux[A, B]): Repr[B] =
    ???

  final def flattenToSeq[B](parallelism: Int)(implicit ev: Streamable.Aux[A, B]): Repr[B] =
    ???

  final def fold[B](zero: B)(f: (B, A) ⇒ B): Repr[B] =
    append(new FoldStage(zero.asInstanceOf[AnyRef], f.asInstanceOf[(AnyRef, AnyRef) ⇒ AnyRef]))

  final def foldAsync[B](zero: B)(f: (B, A) ⇒ Future[B]): Repr[B] =
    ???

  /**
    * @param maxSubstreams the maximum number of sub-streams allowed. Exceeding this limit causes to stream to be
    *                      completed with an [[IllegalStateException]].
    * @param reopenCancelledSubs if `true` cancellation of a sub-stream will trigger a new sub-stream for the respective
    *                            key to be emitted to the downstream (whenever a respective element arrives),
    *                            if `false` all elements that are keyed to a cancelled sub-stream will simply be dropped
    * @param eagerComplete if `true` the cancellation of the (main) downstream will immediately be propagated to upstream
    *                      and all sub-stream will be completed,
    *                      if `false` the cancellation of the (main) downstream will keep the stream running, but
    *                      cause all elements keyed to not yet open sub-streams to be dropped.
    * @param f the key function. Must not return `null` for any element. Otherwise the stream is completed with a
    *          [[RuntimeException]].
    */
  final def groupBy[K](maxSubstreams: Int, reopenCancelledSubs: Boolean = false, eagerComplete: Boolean = false)(
      f: A ⇒ K): Repr[Spout[A]] =
    append(new GroupByStage(maxSubstreams, reopenCancelledSubs, eagerComplete, f.asInstanceOf[AnyRef ⇒ AnyRef]))

  final def grouped(groupSize: Int, emitSingleEmpty: Boolean = false): Repr[immutable.Seq[A]] =
    groupedTo[immutable.Seq](groupSize, emitSingleEmpty)

  final def groupedTo[M[+ _]](groupSize: Int, emitSingleEmpty: Boolean = false)(
      implicit cbf: CanBuildFrom[M[A], A, M[A]]): Repr[M[A]] =
    append(
      new GroupedStage(
        groupSize,
        emitSingleEmpty,
        cbf.apply().asInstanceOf[scala.collection.mutable.Builder[Any, AnyRef]]))

  /**
    * Groups incoming elements received within the given `duration` into [[Vector]] instances that have at least one and
    * at most `maxSize` elements. A group is emitted when `maxSize` has been reached or the `duration` since the last
    * emit has expired. If no elements are received within the `duration` then nothing is emitted at time expiration,
    * but the next incoming element will be emitted immediately after reception as part of a single-element group.
    *
    * @param maxSize the maximum size of the emitted Vector instances, must be > 0
    * @param duration the time period over which to aggregate, must be > 0
    */
  final def groupedWithin(maxSize: Int, duration: FiniteDuration): Repr[Vector[A]] =
    append(new GroupedWithinStage(maxSize, duration))

  final def headAndTail: Repr[(A, Spout[A])] =
    via(Pipe[A].prefixAndTail(1).map {
      case (prefix, tail) ⇒
        if (prefix.isEmpty) throw new NoSuchElementException("head of empty stream") else prefix.head → tail
    } named "headAndTail")

  /**
    * The underlying representation without additional stage appended.
    */
  def identity: Repr[A]

  final def injectToAny(parallelism: Int): Repr[Spout[A]] =
    ???

  final def injectBroadcast(parallelism: Int): Repr[Spout[A]] =
    ???

  final def injectRoundRobin(parallelism: Int): Repr[Spout[A]] =
    ???

  final def injectSequential: Repr[Spout[A]] =
    append(new InjectSequentialStage)

  final def interleave[B >: A](other: Spout[B], segmentSize: Int = 1, eagerComplete: Boolean = false): Repr[B] =
    attach(other).fanInRoundRobin(segmentSize, eagerComplete)

  final def intersperse[B >: A](inject: B): Repr[B] =
    intersperse(null.asInstanceOf[B], inject, null.asInstanceOf[B])

  final def intersperse[B >: A](start: B, inject: B, end: B): Repr[B] =
    append(new IntersperseStage(start.asInstanceOf[AnyRef], inject.asInstanceOf[AnyRef], end.asInstanceOf[AnyRef]))

  final def last: Repr[A] =
    takeLast(1)

  final def logEvent(marker: String, log: (String, StreamEvent[A]) ⇒ Unit = defaultLogEvent): Repr[A] =
    onSignal(log(marker, _))

  final def map[B](f: A ⇒ B): Repr[B] =
    append(new MapStage(f.asInstanceOf[AnyRef ⇒ AnyRef]))

  final def mapAsync[B](parallelism: Int)(f: A ⇒ Future[B]): Repr[B] =
    map(a ⇒ () ⇒ f(a)).flattenConcat(parallelism)

  final def mapAsyncUnordered[B](parallelism: Int)(f: A ⇒ Future[B]): Repr[B] =
    map(a ⇒ () ⇒ f(a)).flattenMerge(parallelism)

  final def merge[B >: A](other: Spout[B], eagerComplete: Boolean = false): Repr[B] =
    attach(other).fanInMerge(eagerComplete)

  final def mergeSorted[B >: A: Ordering](other: Spout[B], eagerComplete: Boolean = false): Repr[B] =
    attach(other).fanInSorted(eagerComplete)

  final def mergeToEither[B](right: Spout[B]): Repr[Either[A, B]] =
    map(Left[A, B]).attach(right.map(Right[A, B])).fanInToSum[Either[A, B]]()

  final def multiply(factor: Int): Repr[A] =
    via(Pipe[A].flatMap(x ⇒ Iterator.fill(factor)(x)) named "multiply")

  final def orElse[B >: A](other: Spout[B]): Repr[B] =
    attach(other).fanInConcat(stopAfterFirstNonEmpty = true)

  final def nop: Repr[A] =
    append(new NopStage)

  final def onCancel(callback: ⇒ Unit): Repr[A] =
    onSignalPF { case StreamEvent.Cancel ⇒ callback }

  final def onComplete(callback: ⇒ Unit): Repr[A] =
    onSignalPF { case StreamEvent.OnComplete ⇒ callback }

  final def onElement(callback: A ⇒ Unit): Repr[A] =
    onSignalPF { case StreamEvent.OnNext(element) ⇒ callback(element) }

  final def onError(callback: Throwable ⇒ Unit): Repr[A] =
    onSignalPF { case StreamEvent.OnError(cause) ⇒ callback(cause) }

  final def onSignal(callback: StreamEvent[A] ⇒ Unit): Repr[A] =
    append(new OnSignalStage(callback.asInstanceOf[StreamEvent[Any] ⇒ Unit]))

  final def onSignalPF(callback: PartialFunction[StreamEvent[A], Unit]): Repr[A] =
    onSignal(ev ⇒ callback.applyOrElse(ev, dropFunc))

  final def onRequest(callback: Int ⇒ Unit): Repr[A] =
    onSignalPF { case StreamEvent.Request(count) ⇒ callback(count) }

  final def onStart(callback: () ⇒ Unit): Repr[A] =
    append(new OnStartStage(callback))

  final def onTerminate(callback: Option[Throwable] ⇒ Unit): Repr[A] =
    onSignalPF {
      case StreamEvent.OnComplete     ⇒ callback(None)
      case StreamEvent.OnError(cause) ⇒ callback(Some(cause))
    }

  final def prefixAndTail(n: Int): Repr[(immutable.Seq[A], Spout[A])] =
    prefixAndTailTo[immutable.Seq](n)

  final def prefixAndTailTo[M[+ _]](n: Int)(implicit cbf: CanBuildFrom[M[A], A, M[A]]): Repr[(M[A], Spout[A])] =
    append(new PrefixAndTailStage(n, cbf.apply().asInstanceOf[scala.collection.mutable.Builder[Any, AnyRef]]))

  final def protect[B](recreate: Option[Throwable] => Pipe[A, B]): Repr[B] = ???

  final def recover[B >: A](pf: PartialFunction[Throwable, B]): Repr[B] =
    via(Pipe[A].recoverWith[B](1)(pf.andThen(Spout.one)) named "recover")

  final def recoverToTry: Repr[Try[A]] =
    via(Pipe[A].map(Success(_)).recover { case e: Throwable ⇒ Failure(e) } named "recoverToTry")

  final def recoverWith[B >: A](maxRecoveries: Long)(pf: PartialFunction[Throwable, Spout[B]]): Repr[B] =
    append(new RecoverWithStage(maxRecoveries, pf.asInstanceOf[PartialFunction[Throwable, Spout[AnyRef]]]))

  final def reduce[B >: A](f: (B, B) ⇒ B): Repr[B] =
    via(Pipe[B].headAndTail.map { case (head, tail) ⇒ tail.fold(head)(f) }.flattenConcat() named "reduce")

  final def sample(d: FiniteDuration): Repr[A] = ???

  final def scan[B](zero: B)(f: (B, A) ⇒ B): Repr[B] =
    append(new ScanStage(zero.asInstanceOf[AnyRef], f.asInstanceOf[(AnyRef, AnyRef) ⇒ AnyRef]))

  final def scanAsync[B](zero: B)(f: (B, A) ⇒ Future[B]): Repr[B] =
    ???

  final def slice(startIndex: Long, length: Long): Repr[A] =
    via(Pipe[A] drop startIndex take length named "slice")

  final def sliceEvery(dropLen: Long, takeLen: Long): Repr[A] =
    via(Pipe[A].injectSequential.flatMap(_.slice(dropLen, takeLen)) named "sliceEvery")

  final def sliding(windowSize: Int): Repr[immutable.Seq[A]] =
    slidingTo[immutable.Seq](windowSize)

  final def slidingTo[M[+ _]](windowSize: Int)(implicit cbf: CanBuildFrom[M[A], A, M[A]],
                                               ev: M[A] <:< immutable.Seq[A]): Repr[M[A]] = {
    requireArg(windowSize > 0, "windowSize must be > 0")
    val builder = cbf.apply()
    prefixAndTailTo[M](windowSize) flatMap {
      case (prefix, tail) =>
        val p = ev(prefix)
        p.size match {
          case `windowSize` =>
            val buffer = new RingBuffer[A](roundUpToPowerOf2(windowSize))
            p.foreach(buffer.unsafeWrite)
            tail.scan(prefix) { (_, elem) ⇒
              buffer.unsafeDropHead()
              buffer.unsafeWrite(elem)
              builder.clear()
              buffer.foreach(builder += _)
              builder.result()
            }
          case 0 => Spout.empty
          case _ => Spout.one(prefix)
        }
    }
  }

  final def split(f: A ⇒ Split.Command, eagerCancel: Boolean = true): Repr[Spout[A]] =
    append(new SplitStage(f.asInstanceOf[AnyRef => Split.Command], eagerCancel: Boolean))

  final def splitAfter(f: A ⇒ Boolean, eagerCancel: Boolean = true): Repr[Spout[A]] =
    via(Pipe[A].split(x => if (f(x)) Split.EmitComplete else Split.Emit, eagerCancel) named "splitAfter")

  final def splitWhen(f: A ⇒ Boolean, eagerCancel: Boolean = true): Repr[Spout[A]] =
    via(Pipe[A].split(x => if (f(x)) Split.CompleteEmit else Split.Emit, eagerCancel) named "splitWhen")

  final def take(count: Long): Repr[A] =
    append(new TakeStage(count))

  final def takeEveryNth(n: Long): Repr[A] = {
    requireArg(n > 0, "`count` must be > 0")
    via(Pipe[A].injectSequential.flatMap(_.elementAt(n - 1)) named "takeEvery")
  }

  final def takeLast(n: Int): Repr[A] = {
    val pipe =
      if (n > 0) {
        Pipe[A]
          .fold(new RingBuffer[A](roundUpToPowerOf2(n))) { (buf, elem) ⇒
            if (buf.count == n) buf.unsafeDropHead()
            buf.unsafeWrite(elem)
            buf
          }
          .flattenConcat()
      } else Pipe[A].drop(Long.MaxValue)
    via(pipe named "takeLast")
  }

  final def takeWhile(predicate: A ⇒ Boolean): Repr[A] =
    append(new TakeWhileStage(predicate.asInstanceOf[Any ⇒ Boolean]))

  final def takeWithin(d: FiniteDuration): Repr[A] =
    append(new TakeWithinStage(d))

  final def tee(drain: Drain[A, Unit], eagerCancel: Boolean = false): Repr[A] =
    via(Pipe[A].fanOutBroadcast(eagerCancel).sub.to(drain).subContinue named "tee")

  final def throttle(elements: Int, per: FiniteDuration, burst: Int = 1): Repr[A] =
    throttle(elements, per, burst, oneIntFunc)

  final def throttle(cost: Int, per: FiniteDuration, burst: Int, costFn: A ⇒ Int): Repr[A] =
    append(new ThrottleStage(cost, per, burst, costFn.asInstanceOf[AnyRef ⇒ Int]))

  def via[B](pipe: A =>> B): Repr[B]

  // timeout = time after demand signal for first element
  final def withCompletionTimeout(timeout: FiniteDuration): Repr[A] =
    append(new WithCompletionTimeoutStage(timeout))

  // timeout = time after demand signal for element or previous element, whatever happened later
  final def withIdleTimeout(timeout: FiniteDuration): Repr[A] =
    append(new WithIdleTimeoutStage(timeout))

  // timeout = time after demand signal for first element
  final def withInitialTimeout(timeout: FiniteDuration): Repr[A] =
    append(new WithInitialTimeoutStage(timeout))

  final def withLimit(maxElements: Long): Repr[A] =
    withLimitWeighted(maxElements, _ ⇒ 1)

  final def withLimitWeighted(max: Long, cost: A ⇒ Long): Repr[A] =
    append(new WithLimitStage(max, cost.asInstanceOf[AnyRef ⇒ Long]))

  final def zip[B](other: Spout[B]): Repr[(A, B)] = {
    val moduleID = Module.ID("zip")
    moduleID.addBoundary(Module.Boundary.OuterEntry(other.inport.stage))
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
      requireArg(subs.nonEmpty, "Cannot `attachAll` without open sub-streams")
      new FanIn0(this.subs :++ subs, rawWrap)
    }

    def fanInConcat(stopAfterFirstNonEmpty: Boolean = false): Repr[Sup] =
      wrap(if (stopAfterFirstNonEmpty) new FirstNonEmptyStage(subs) else new ConcatStage(subs))

    def fanInMerge(eagerComplete: Boolean = false): Repr[Sup] =
      wrap(new MergeStage(subs, eagerComplete))

    def fanInRoundRobin(segmentSize: Int = 1, eagerComplete: Boolean = false): Repr[Sup] =
      ???

    def fanInSorted(eagerComplete: Boolean = false)(implicit ord: Ordering[Sup]): Repr[Sup] =
      ???

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
      private[core] val subs: InportList,
      protected val rawWrap: Inport ⇒ Repr[_]) {

    type FI[LL <: HList, CC <: Coproduct, S] <: FanIn[LL, CC, S, Repr]

    protected def copy[LL <: HList, CC <: Coproduct, S](subs: InportList): FI[LL, CC, S] =
      new FanIn(subs, rawWrap).asInstanceOf[FI[LL, CC, S]]

    final def attach[T, Sup2, P <: HList, Q <: Coproduct](sub: Spout[T])(
        implicit ev0: Lub[Sup, T, Sup2],
        ev1: ops.hlist.Prepend.Aux[L, T :: HNil, P],
        ev2: ops.coproduct.Prepend.Aux[C, T :+: CNil, Q]): FI[P, Q, Sup2] =
      copy(subs :+ sub.inport)

    final def attachLeft[T, Sup2](sub: Spout[T])(implicit ev: Lub[Sup, T, Sup2]): FI[T :: L, T :+: C, Sup2] =
      copy(sub.inport +: subs)

    final def attachAll[S, Sup2 >: Sup](subs: Traversable[S])(
        implicit ev: Streamable.Aux[S, Sup2]): FanIn0[Sup2, Repr] = {
      requireArg(subs.nonEmpty, "Cannot `attachAll` without open sub-streams")
      new FanIn0(this.subs :++ subs, rawWrap)
    }

    final def fanInConcat(stopAfterFirstNonEmpty: Boolean = false)(implicit ev: FanInReq[L]): Repr[Sup] =
      wrap(if (stopAfterFirstNonEmpty) new FirstNonEmptyStage(subs) else new ConcatStage(subs))

    final def fanInMerge(eagerComplete: Boolean = false)(implicit ev: FanInReq[L]): Repr[Sup] =
      wrap(new MergeStage(subs, eagerComplete))

    final def fanInRoundRobin(segmentSize: Int = 1, eagerComplete: Boolean = false)(
        implicit ev: FanInReq[L]): Repr[Sup] =
      ???

    final def fanInSorted(eagerComplete: Boolean = false)(implicit ev: FanInReq[L], ord: Ordering[Sup]): Repr[Sup] =
      ???

    final def fanInToCoproduct(eagerComplete: Boolean = true)(implicit ev: FanInReq[L]): Repr[C] =
      ???

    final def fanInToHList(implicit ev: FanInReq[L]): Repr[L] =
      wrap(new ToProductStage(Stage.Kind.FanIn.ToHList, subs, _.toHList()))

    final def fanInToProduct[T](implicit ev: FanInReq[L], gen: ToProduct[T, L]): Repr[T] =
      fanInToHList.asInstanceOf[StreamOps[L]].map(l ⇒ gen from l).asInstanceOf[Repr[T]]

    final def fanInToSum[T](eagerComplete: Boolean = true)(implicit ev: FanInReq[L], gen: Summable[T, C]): Repr[T] =
      fanInToCoproduct(eagerComplete).asInstanceOf[StreamOps[C]].map(c ⇒ gen from c).asInstanceOf[Repr[T]]

    final def fanInToTuple(implicit ev: FanInReq[L], t: Tuplable[L]): Repr[t.Out] =
      wrap(new ToProductStage(Stage.Kind.FanIn.ToTuple, subs, _.toTuple))

    def fromFanInVia[P <: HList, R, Out](joined: Module.TypeLogic.Joined[L, P, R])(
        implicit vr: ViaResult[P, Piping[R], Repr, Out]): Out = {
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
  final class FanOut[A, L <: HList, C <: Coproduct, Sup, Repr[_]] private[core] (private[core] val base: Inport,
                                                                                 _subs: InportList,
                                                                                 _wrap: Inport ⇒ Repr[_])
      extends FanIn[L, C, Sup, Repr](_subs, _wrap) {

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
  final class BranchOut[A <: HList, L <: HList, C <: Coproduct, Sup, Repr[_]] private[core] (ins: InportList,
                                                                                             _subs: InportList,
                                                                                             _wrap: Inport ⇒ Repr[_])
      extends FanIn[L, C, Sup, Repr](_subs, _wrap) {

    type FI[LL <: HList, CC <: Coproduct, S]                   = BranchOut[A, LL, CC, S, Repr]
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
      fo: F,
      spout: Spout[A])
      extends StreamOps[A] {
    type Repr[X] = SubStreamOps[X, L, C, Sup, FRepr, F]

    protected def base: Inport                         = spout.inport
    protected def wrap: Inport ⇒ Repr[_]               = in ⇒ new SubStreamOps(fo, new Spout(in))
    protected def append[B](stage: StageImpl): Repr[B] = new SubStreamOps(fo, spout.append(stage))

    def identity: Repr[A] = this

    def to[R](drain: Drain[A, Unit]): F = {
      drain.consume(spout)
      fo
    }

    def via[B](pipe: A =>> B): Repr[B] = new SubStreamOps(fo, spout via pipe)

    def via[P <: HList, R, Out](joined: Module.TypeLogic.Joined[A :: HNil, P, R])(
        implicit vr: TypeLogic.ViaResult[P, F, Repr, Out]): Out = {
      val out = ModuleImpl(joined.module)(InportList(spout.inport))
      val result = vr.id match {
        case 0 ⇒ fo
        case 1 ⇒ new SubStreamOps[A, L, C, Sup, FRepr, F](fo, new Spout(out.asInstanceOf[InportList].in))
        case 2 ⇒ new StreamOps.FanIn(out.asInstanceOf[InportList], wrap)
      }
      result.asInstanceOf[Out]
    }

    def end[Sup2, P <: HList, Q <: Coproduct](implicit ev0: Lub[Sup, A, Sup2],
                                              ev1: ops.hlist.Prepend.Aux[L, A :: HNil, P],
                                              ev2: ops.coproduct.Prepend.Aux[C, A :+: CNil, Q]): F#FI[P, Q, Sup2] =
      fo.attach(spout).asInstanceOf[F#FI[P, Q, Sup2]]
  }

  @implicitNotFound(msg = "Cannot fan-in here. You need to have at least two open fan-in sub-streams.")
  private type FanInReq[L <: HList] = IsHCons2[L]

  @implicitNotFound(msg = "Cannot assemble product type `${T}` from `${L}`.")
  private type ToProduct[T, L <: HList] = Generic.Aux[T, L]

  @implicitNotFound(msg = "Cannot auto-deconstruct `${T}`. You might want to `map` to a case class first.")
  private type FromProduct[T, L <: HList] = Generic.Aux[T, L]

  @implicitNotFound(msg = "Cannot assemble sum type `${T}` from `${C}`.")
  private type Summable[T, C <: Coproduct] = Generic.Aux[T, C]

  @implicitNotFound(msg = "Cannot convert `${L}` into a tuple.")
  private type Tuplable[L <: HList] = Tupler[L]

  @implicitNotFound(
    msg = "Illegal substream definition! All available fan-out sub-streams have already been consumed.")
  private type SubReq[L <: HList] = IsHCons[L]

  @implicitNotFound(msg = "`subContinue` is only possible with exactly one remaining fan-out sub-stream unconsumed!")
  private type SubContinueReq0[L <: HList] = IsSingle[L]
  @implicitNotFound(
    msg = "`subContinue` is only possible without any previous fan-in sub-streams! Here you have: ${L}.")
  private type SubContinueReq1[L <: HList] = IsHNil[L]

  @implicitNotFound(
    msg = "Cannot continue stream definition here! You still have at least one unconsumed fan-out sub-stream.")
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
