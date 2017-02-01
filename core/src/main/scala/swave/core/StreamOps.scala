/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import scala.annotation.{compileTimeOnly, implicitNotFound, tailrec}
import scala.collection.generic.CanBuildFrom
import scala.collection.{immutable, mutable}
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
abstract class StreamOps[A] private[core] { self ⇒
  import StreamOps._

  type Repr[T] <: StreamOps[T] { type Repr[X] <: self.Repr[X] }

  protected def base: Inport
  protected def wrap[T](inport: Inport): Repr[T]
  protected def append[T](stage: StageImpl): Repr[T]

  final def async(dispatcherId: String = ""): Repr[A] =
    append(new AsyncDispatcherStage(dispatcherId))

  final def asyncBoundary(dispatcherId: String = "", bufferSize: Int = 32): Repr[A] =
    append(new AsyncBoundaryStage(dispatcherId)).buffer(bufferSize)

  final def attach[T, S, O](sub: Spout[T])(implicit ev: Lub[A, T, O]): FanIn[A :: T :: HNil, O] =
    new FanIn(InportList(base) :+ sub.inport)

  final def attach[L <: HList, S](branchOut: Spout[_]#BranchOut[L, _, _])(
      implicit u: HLub.Aux[A :: L, S]): FanIn[A :: L, S] =
    new FanIn(base +: branchOut.subs)

  final def attach[L <: HList, S, SS](fanIn: Spout[_]#FanIn[L, S])(implicit ev: Lub[A, S, SS]): FanIn[A :: L, SS] =
    new FanIn(base +: fanIn.subs)

  final def attachAll[SS, S >: A](subs: Traversable[SS])(implicit ev: Streamable.Aux[SS, S]): FanIn0[S] = {
    requireArg(subs.nonEmpty, "Cannot `attachAll` without open sub-streams")
    new FanIn0(InportList(base) :++ subs)
  }

  final def attachLeft[T, S, O](sub: Spout[T])(implicit ev: Lub[A, T, O]): FanIn[T :: A :: HNil, O] =
    new FanIn(base +: InportList(sub.inport))

  final def attachN[T, O](n: Nat, fo: Spout[T]#FanOut[_, _])(implicit f: Fill[n.N, T],
                                                             ti: ToInt[n.N],
                                                             lub: Lub[A, T, O]): FanIn[A :: f.Out, O] =
    new FanIn(base +: InportList.fill(ti(), attachNop(fo.base)))

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

  final def ++[B >: A](other: Spout[B]): Repr[B] = concat(other)

  final def concat[B >: A](other: Spout[B]): Repr[B] =
    attach(other).fanInConcat()

  final def conflate[B >: A](aggregate: (B, A) ⇒ B): Repr[B] =
    conflateWithSeed[B](identityFunc)(aggregate)

  final def conflateToLast[B >: A]: Repr[B] =
    conflate[B]((_, x) => x)

  final def conflateWithSeed[B](lift: A ⇒ B)(aggregate: (B, A) ⇒ B): Repr[B] =
    append(new ConflateStage(lift.asInstanceOf[Any ⇒ AnyRef], aggregate.asInstanceOf[(Any, Any) ⇒ AnyRef]))

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

  /**
    * Rate-detaches the downstream from the upstream by allowing the downstream to consume elements faster than the
    * upstream produces them. Each element coming in from upstream is passed through the given `extrapolate` function.
    * The produced iterator is then pulled from at least once (if non-empty). Afterwards, if the downstream is ready to
    * consume more elements but the upstream hasn't delivered any yet the iterator will be drained until it has no more
    * elements or the next element from upstream arrives.
    *
    * If the upstream produces elements at a faster rate than the downstream can consume them each iterator produced by
    * the `extrapolate` function will only ever have its first element pulled and the upstream will be backpressured,
    * i.e. the downstream will slow down the upstream.
    *
    * @param zero iterator used for supplying elements to downstream before the first element arrives from upstream,
    *             only pulled from if the first demand from downstream arrives before the first element from upstream.
    * @param extrapolate function producing the elements that each element from upstream is expanded to
    */
  final def expand[B](zero: Iterator[B], extrapolate: A ⇒ Iterator[B]): Repr[B] =
    append(new ExpandStage(zero.asInstanceOf[Iterator[AnyRef]], extrapolate.asInstanceOf[Any ⇒ Iterator[AnyRef]]))

  final def fanOutBroadcast(bufferSize: Int = 0,
                            requestStrategy: Buffer.RequestStrategy = Buffer.RequestStrategy.WhenHalfEmpty,
                            eagerCancel: Boolean = false): FanOut[HNil, Nothing] = {
    requireArg(bufferSize >= 0, "`bufferSize` must be >= 0")
    val stage =
      if (bufferSize == 0) new FanOutBroadcastStage(eagerCancel)
      else new FanOutBroadcastBufferedStage(bufferSize, requestStrategy(bufferSize), eagerCancel)
    new FanOut(append(stage).base, InportList.empty)
  }

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
  final def fanOutRoundRobin(eagerCancel: Boolean = false): FanOut[HNil, Nothing] =
    ???

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
  final def fanOutSequential(eagerCancel: Boolean = false): FanOut[HNil, Nothing] =
    ???

  final def fanOutSwitch(n: Nat, eagerCancel: Boolean = false)(
      f: A ⇒ Int)(implicit ti: ToInt[n.N], fl: Fill[n.N, A]): BranchOut[fl.Out, HNil, Nothing] =
    fanOutSwitch[n.N](f, eagerCancel)

  final def fanOutSwitch[N <: Nat](f: A ⇒ Int)(implicit ti: ToInt[N],
                                               fl: Fill[N, A]): BranchOut[fl.Out, HNil, Nothing] =
    fanOutSwitch[N](f, eagerCancel = false)

  final def fanOutSwitch[N <: Nat](f: A ⇒ Int, eagerCancel: Boolean)(
      implicit ti: ToInt[N],
      fl: Fill[N, A]): BranchOut[fl.Out, HNil, Nothing] = {
    val branchCount = ti()
    val base        = append(new FanOutSwitchStage(branchCount, f.asInstanceOf[AnyRef ⇒ Int], eagerCancel)).base
    new BranchOut(InportList.fill(branchCount, attachNop(base)), InportList.empty)
  }

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
  final def fanOutToAny(eagerCancel: Boolean = false): FanOut[HNil, Nothing] =
    ???

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
  final def fanOutUnZip[L <: HList](eagerCancel: Boolean = false)(
      implicit ev: FromProduct[A, L]): BranchOut[L, HNil, Nothing] =
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
    via(Pipe[A] map f flattenConcat parallelism named "flatMap")

  final def flattenConcat[B](parallelism: Int = 1)(implicit ev: Streamable.Aux[A, B]): Repr[B] =
    append(new FlattenConcatStage(ev.asInstanceOf[Streamable.Aux[Any, AnyRef]], parallelism))

  final def flattenMerge[B](parallelism: Int)(implicit ev: Streamable.Aux[A, B]): Repr[B] =
    append(new FlattenMergeStage(ev.asInstanceOf[Streamable.Aux[Any, AnyRef]], parallelism))

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
  final def flattenRoundRobin[B](parallelism: Int)(implicit ev: Streamable.Aux[A, B]): Repr[B] =
    ???

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
  final def flattenSorted[B: Ordering](parallelism: Int)(implicit ev: Streamable.Aux[A, B]): Repr[B] =
    ???

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
  final def flattenToSeq[B](parallelism: Int)(implicit ev: Streamable.Aux[A, B]): Repr[immutable.Seq[B]] =
    ???

  final def fold[B](zero: B)(f: (B, A) ⇒ B): Repr[B] =
    append(new FoldStage(zero.asInstanceOf[AnyRef], f.asInstanceOf[(Any, Any) ⇒ AnyRef]))

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
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
    append(new GroupByStage(maxSubstreams, reopenCancelledSubs, eagerComplete, f.asInstanceOf[Any ⇒ AnyRef]))

  final def grouped(groupSize: Int, emitSingleEmpty: Boolean = false): Repr[immutable.Seq[A]] =
    groupedTo[immutable.Seq](groupSize, emitSingleEmpty)

  final def groupedTo[M[+ _]](groupSize: Int, emitSingleEmpty: Boolean = false)(
      implicit cbf: CanBuildFrom[M[A], A, M[A]]): Repr[M[A]] =
    append(new GroupedStage(groupSize, emitSingleEmpty, cbf.apply().asInstanceOf[mutable.Builder[Any, AnyRef]]))

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
  def identity: Repr[A] = asInstanceOf[Repr[A]]

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
  final def injectToAny(parallelism: Int): Repr[Spout[A]] =
    ???

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
  final def injectBroadcast(parallelism: Int): Repr[Spout[A]] =
    ???

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
  final def injectRoundRobin(parallelism: Int): Repr[Spout[A]] =
    ???

  final def injectSequential(bufferSize: Int = -1): Repr[Spout[A]] = {
    require(bufferSize != 0, "bufferSize must not be zero")
    append(if (bufferSize == 1) new InjectSequentialStage else new InjectSequentialBufferedStage(bufferSize))
  }

  @compileTimeOnly("Not yet implemented") // TODO: remove when `fanInRoundRobin` is implemented
  final def interleave[B >: A](other: Spout[B], segmentSize: Int = 1, eagerComplete: Boolean = false): Repr[B] =
    attach(other).fanInRoundRobin(segmentSize, eagerComplete)

  final def intersperse[B >: A](inject: B): Repr[B] =
    intersperse(null.asInstanceOf[B], inject, null.asInstanceOf[B])

  final def intersperse[B >: A](start: B, inject: B, end: B): Repr[B] =
    append(new IntersperseStage(start.asInstanceOf[AnyRef], inject.asInstanceOf[AnyRef], end.asInstanceOf[AnyRef]))

  final def last: Repr[A] =
    takeLast(1)

  final def logSignal(marker: String, log: (String, StreamEvent[A]) ⇒ Unit = defaultLogSignal): Repr[A] =
    onSignal(log(marker, _))

  final def map[B](f: A ⇒ B): Repr[B] =
    append(new MapStage(f.asInstanceOf[Any ⇒ AnyRef]))

  final def mapAsync[B](parallelism: Int)(f: A ⇒ Future[B]): Repr[B] =
    map(a ⇒ () ⇒ f(a)).flattenConcat(parallelism).async()

  final def mapAsyncUnordered[B](parallelism: Int)(f: A ⇒ Future[B]): Repr[B] =
    map(a ⇒ () ⇒ f(a)).flattenMerge(parallelism).async()

  final def merge[B >: A](other: Spout[B], eagerComplete: Boolean = false): Repr[B] =
    attach(other).fanInMerge(eagerComplete)

  @compileTimeOnly("Not yet implemented") // TODO: remove when `fanInSorted` is implemented
  final def mergeSorted[B >: A: Ordering](other: Spout[B], eagerComplete: Boolean = false): Repr[B] =
    attach(other).fanInSorted(eagerComplete)

  @compileTimeOnly("Not yet implemented") // TODO: remove when `fanInToCoproduct` is implemented
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

  final def prefixAndTailTo[S[+ _]](n: Int)(implicit cbf: CanBuildFrom[S[A], A, S[A]]): Repr[(S[A], Spout[A])] =
    append(new PrefixAndTailStage(n, cbf.apply().asInstanceOf[scala.collection.mutable.Builder[Any, AnyRef]]))

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
  final def protect[B](recreate: Option[Throwable] => Pipe[A, B]): Repr[B] = ???

  final def recover[B >: A](pf: PartialFunction[Throwable, B]): Repr[B] =
    via(Pipe[B].recoverWith(1)(pf.andThen(Spout.one)) named "recover")

  final def recoverToTry: Repr[Try[A]] =
    via(Pipe[A].map(Success(_)).recover { case e: Throwable ⇒ Failure(e) } named "recoverToTry")

  final def recoverWith[B >: A](maxRecoveries: Long)(pf: PartialFunction[Throwable, Spout[B]]): Repr[B] =
    append(new RecoverWithStage(maxRecoveries, pf.asInstanceOf[PartialFunction[Throwable, Spout[AnyRef]]]))

  final def reduce[B >: A](f: (B, B) ⇒ B): Repr[B] =
    via(Pipe[B].headAndTail.map { case (head, tail) ⇒ tail.fold(head)(f) }.flattenConcat() named "reduce")

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
  final def sample(d: FiniteDuration): Repr[A] = ???

  final def scan[B](zero: B)(f: (B, A) ⇒ B): Repr[B] =
    append(new ScanStage(zero.asInstanceOf[AnyRef], f.asInstanceOf[(Any, Any) ⇒ AnyRef]))

  @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
  final def scanAsync[B](zero: B)(f: (B, A) ⇒ Future[B]): Repr[B] =
    ???

  final def slice(startIndex: Long, length: Long): Repr[A] =
    via(Pipe[A] drop startIndex take length named "slice")

  final def sliceEvery(dropLen: Long, takeLen: Long): Repr[A] =
    via(Pipe[A].injectSequential().flatMap(_.slice(dropLen, takeLen)) named "sliceEvery")

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
    append(new SplitStage(f.asInstanceOf[Any => Split.Command], eagerCancel: Boolean))

  final def splitAfter(f: A ⇒ Boolean, eagerCancel: Boolean = true): Repr[Spout[A]] =
    via(Pipe[A].split(x => if (f(x)) Split.EmitComplete else Split.Emit, eagerCancel) named "splitAfter")

  final def splitWhen(f: A ⇒ Boolean, eagerCancel: Boolean = true): Repr[Spout[A]] =
    via(Pipe[A].split(x => if (f(x)) Split.CompleteEmit else Split.Emit, eagerCancel) named "splitWhen")

  final def take(count: Long): Repr[A] =
    append(new TakeStage(count))

  final def takeEveryNth(n: Long): Repr[A] = {
    requireArg(n > 0, "`count` must be > 0")
    via(Pipe[A].injectSequential().flatMap(_.elementAt(n - 1)) named "takeEvery")
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
    via(Pipe[A].fanOutBroadcast(eagerCancel = eagerCancel).sub.to(drain).subContinue named "tee")

  final def throttle(elements: Int, per: FiniteDuration, burst: Int = 1): Repr[A] =
    throttle(elements, per, burst, oneIntFunc)

  final def throttle(cost: Int, per: FiniteDuration, burst: Int, costFn: A ⇒ Int): Repr[A] =
    append(new ThrottleStage(cost, per, burst, costFn.asInstanceOf[Any ⇒ Int]))

  def via[B](pipe: Pipe[A, B]): Repr[B]

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
    append(new WithLimitStage(max, cost.asInstanceOf[Any ⇒ Long]))

  final def zip[B](other: Spout[B]): Repr[(A, B)] = {
    val moduleID = Module.ID("zip")
    moduleID.addBoundary(Module.Boundary.OuterEntry(other.inport.stageImpl))
    via(Pipe[A].attach(other).fanInToTuple named moduleID)
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////////////

  /**
    * Homogeneous fan-in, where all fan-in streams have the same type.
    *
    * @tparam S  super-type of all fan-in sub-streams
    */
  final class FanIn0[S] private[StreamOps] (subs: InportList) {

    def attach[SS >: S](sub: Spout[SS]): FanIn0[SS] =
      new FanIn0(subs :+ sub.inport)

    def attachLeft[SS >: S](sub: Spout[SS]): FanIn0[SS] =
      new FanIn0(sub.inport +: subs)

    def attachAll[T, SS >: S](subs: Traversable[T])(implicit ev: Streamable.Aux[T, SS]): FanIn0[SS] = {
      requireArg(subs.nonEmpty, "Cannot `attachAll` without open sub-streams")
      new FanIn0(this.subs :++ subs)
    }

    def fanInConcat(stopAfterFirstNonEmpty: Boolean = false): Repr[S] =
      wrap(if (stopAfterFirstNonEmpty) new FirstNonEmptyStage(subs) else new ConcatStage(subs))

    def fanInMerge(eagerComplete: Boolean = false): Repr[S] =
      wrap(new MergeStage(subs, eagerComplete))

    @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
    def fanInRoundRobin(segmentSize: Int = 1, eagerComplete: Boolean = false): Repr[S] =
      ???

    @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
    def fanInSorted(eagerComplete: Boolean = false)(implicit ord: Ordering[S]): Repr[S] =
      ???
  }

  /**
    * Heterogeneous fan-in, where the fan-in streams have potentially differing types.
    *
    * @tparam L    element types of all unterminated fan-in sub-streams as an HList
    * @tparam S  super-type of all unterminated fan-in sub-streams
    */
  sealed class FanIn[L <: HList, S] private[core] (private[core] val subs: InportList) {

    type FI[LL <: HList, SS] <: FanIn[LL, SS]

    protected def copy[LL <: HList, SS](subs: InportList): FI[LL, SS] =
      new FanIn(subs).asInstanceOf[FI[LL, SS]]

    final def attach[T, SS, P <: HList](sub: Spout[T])(implicit ev0: Lub[S, T, SS],
                                                       ev1: ops.hlist.Prepend.Aux[L, T :: HNil, P]): FI[P, SS] =
      copy(subs :+ sub.inport)

    final def attachLeft[T, SS](sub: Spout[T])(implicit ev: Lub[S, T, SS]): FI[T :: L, SS] =
      copy(sub.inport +: subs)

    final def attachAll[T, SS >: S](subs: Traversable[T])(implicit ev: Streamable.Aux[T, SS]): FanIn0[SS] = {
      requireArg(subs.nonEmpty, "Cannot `attachAll` without open sub-streams")
      new FanIn0(this.subs :++ subs)
    }

    final def fanInConcat(stopAfterFirstNonEmpty: Boolean = false)(implicit ev: FanInReq[L]): Repr[S] =
      wrap(if (stopAfterFirstNonEmpty) new FirstNonEmptyStage(subs) else new ConcatStage(subs))

    final def fanInMerge(eagerComplete: Boolean = false)(implicit ev: FanInReq[L]): Repr[S] =
      wrap(new MergeStage(subs, eagerComplete))

    @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
    final def fanInRoundRobin(segmentSize: Int = 1, eagerComplete: Boolean = false)(
        implicit ev: FanInReq[L]): Repr[S] =
      ???

    @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
    final def fanInSorted(eagerComplete: Boolean = false)(implicit ev: FanInReq[L], ord: Ordering[S]): Repr[S] =
      ???

    @compileTimeOnly("Not yet implemented") // TODO: remove when implemented
    final def fanInToCoproduct[C <: Coproduct](eagerComplete: Boolean = true)(implicit ev: FanInReq[L],
                                                                              tpc: ToCoproduct.Aux[L, C]): Repr[C] =
      ???

    final def fanInToHList(implicit ev: FanInReq[L]): Repr[L] =
      wrap(new ToProductStage(Stage.Kind.FanIn.ToHList, subs, _.toHList()))

    final def fanInToProduct[T](implicit ev: FanInReq[L], gen: ToProduct[T, L]): Repr[T] =
      fanInToHList.map(l ⇒ gen from l)

    @compileTimeOnly("Not yet implemented") // TODO: remove when `fanInToCoproduct` is implemented
    final def fanInToSum[T](eagerComplete: Boolean = true)(implicit ev: FanInReq[L], s: Summable[T, L]): Repr[T] =
      fanInToCoproduct[s.CP](eagerComplete)(ev, s.tpc).map(s.gen.from)

    final def fanInToTuple(implicit ev: FanInReq[L], t: Tuplable[L]): Repr[t.Out] =
      wrap(new ToProductStage(Stage.Kind.FanIn.ToTuple, subs, _.toTuple))

    def fromFanInVia[P <: HList, R, Out](joined: Module.TypeLogic.Joined[L, P, R])(
        implicit vr: ViaResult[P, StreamGraph[R], Repr, Out]): Out = {
      val out = ModuleImpl(joined.module)(subs)
      val result = vr.id match {
        case 0 ⇒ new StreamGraph(out, subs.in.stageImpl)
        case 1 ⇒ wrap(out.asInstanceOf[InportList].in)
        case 2 ⇒ new FanIn(out.asInstanceOf[InportList])
      }
      result.asInstanceOf[Out]
    }

    final def asBranchOut: BranchOut[L, HNil, Nothing] = new BranchOut(subs, InportList.empty)
  }

  /**
    * Open fan-out, where all fan-out sub streams have the same type and there can be arbitrarily many of them.
    *
    * @tparam L    element types of all unterminated fan-in sub-streams as an HList
    * @tparam S  super-type of all unterminated fan-in sub-streams
    */
  final class FanOut[L <: HList, S] private[core] (private[core] val base: Inport, _subs: InportList)
      extends FanIn[L, S](_subs) {

    type FI[LL <: HList, SS] = FanOut[LL, SS]

    override protected def copy[LL <: HList, SS](subs: InportList): FI[LL, SS] = new FanOut(base, subs)

    def sub: SubStreamOps[A, L, S, FanOut[L, S]] = new SubStreamOps(this, new Spout(attachNop(base)))

    def subContinue(implicit ev: SubContinueReq1[L]): Repr[A] = wrap(base)

    def continue(implicit ev: ContinueReq1[L]): Repr[ev.Out] = wrap(subs.in)

    def end(implicit ev: EndReq0[L]): StreamGraph[Unit] = new StreamGraph((), base.stageImpl)

    def subDrains(drains: List[Drain[A, Unit]]): FI[L, S] = {
      @tailrec def rec(remaining: List[Drain[A, Unit]]): Unit =
        if (remaining.nonEmpty) {
          remaining.head.consume(new Spout(base))
          rec(remaining.tail)
        }
      rec(drains)
      this.asInstanceOf[FI[L, S]]
    }
  }

  /**
    * Closed fan-out, where the number of fan-out sub streams and their (potentially differing) types are predefined.
    *
    * @tparam AA    element types of the still unconsumed fan-out sub-streams as an HList
    * @tparam L    element types of all unterminated fan-in sub-streams as an HList
    * @tparam S  super-type of all unterminated fan-in sub-streams
    */
  final class BranchOut[AA <: HList, L <: HList, S] private[core] (ins: InportList, _subs: InportList)
      extends FanIn[L, S](_subs) {

    type FI[LL <: HList, SS] = BranchOut[AA, LL, SS]

    override protected def copy[LL <: HList, SS](subs: InportList): FI[LL, SS] =
      new BranchOut(ins, subs)

    def sub(implicit ev: SubReq[AA]): SubStreamOps[ev.H, L, S, BranchOut[ev.T, L, S]] =
      new SubStreamOps(new BranchOut[ev.T, L, S](ins.tail, subs), new Spout(ins.in))

    def subContinue(implicit ev0: SubContinueReq0[L], ev1: SubContinueReq1[L]): Repr[AA] = wrap(ins.in)

    def continue(implicit ev0: ContinueReq0[L], ev1: ContinueReq1[L]): Repr[ev1.Out] = wrap(subs.in)

    def end(implicit ev0: EndReq0[L], ev1: EndReq1[L]): StreamGraph[Unit] = new StreamGraph((), subs.in.stageImpl)

    // private type ViaBranchOut[LL <: HList, CC <: Coproduct, S] = BranchOut[LL, HNil, CNil, Nothing, Repr]
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
}

object StreamOps {

  val defaultLogSignal: (String, StreamEvent[Any]) ⇒ Unit = { (m, ev) ⇒
    val arrow = if (ev.isInstanceOf[StreamEvent.UpEvent]) '⇠' else '⇢'
    println(s"$m: $arrow $ev")
  }

  /**
    * The operations underneath a fan/branch-out sub.
    */
  final class SubStreamOps[A, L <: HList, S, F <: StreamOps[_]#FanIn[_, _]] private[core] (fo: F, spout: Spout[A])
      extends StreamOps[A] {

    type Repr[T] = SubStreamOps[T, L, S, F]

    protected def base: Inport                         = spout.inport
    protected def wrap[T](inport: Inport): Repr[T]     = new SubStreamOps(fo, new Spout(inport))
    protected def append[T](stage: StageImpl): Repr[T] = new SubStreamOps(fo, spout.append(stage))

    def to(drain: Drain[A, Unit]): F#FI[L, S] = {
      drain.consume(spout)
      fo.asInstanceOf[F#FI[L, S]]
    }

    def via[B](pipe: Pipe[A, B]): Repr[B] = new SubStreamOps(fo, spout via pipe)

    def via[P <: HList, R, Out](joined: Module.TypeLogic.Joined[A :: HNil, P, R])(
        implicit vr: TypeLogic.ViaResult[P, F, Repr, Out]): Out = {
      val out = ModuleImpl(joined.module)(InportList(spout.inport))
      val result = vr.id match {
        case 0 ⇒ fo
        case 1 ⇒ new SubStreamOps(fo, new Spout(out.asInstanceOf[InportList].in))
        case 2 ⇒
          val s = new Spout(null)
          new s.FanIn(out.asInstanceOf[InportList])
      }
      result.asInstanceOf[Out]
    }

    def end[SS, P <: HList](implicit ev0: Lub[S, A, SS], ev: ops.hlist.Prepend.Aux[L, A :: HNil, P]): F#FI[P, SS] =
      fo.attach(spout)(null, null).asInstanceOf[F#FI[P, SS]]
  }

  @implicitNotFound(msg = "Cannot fan-in here. You need to have at least two open fan-in sub-streams.")
  private type FanInReq[L <: HList] = IsHCons2[L]

  @implicitNotFound(msg = "Cannot assemble product type `${T}` from `${L}`.")
  private type ToProduct[T, L <: HList] = Generic.Aux[T, L]

  @implicitNotFound(msg = "Cannot auto-deconstruct `${T}`. You might want to `map` to a case class first.")
  private type FromProduct[T, L <: HList] = Generic.Aux[T, L]

  @implicitNotFound(msg = "Cannot convert `${L}` into a tuple.")
  private type Tuplable[L <: HList] = Tupler[L]

  @implicitNotFound(msg = "Illegal substream definition! All available fan-out sub-streams have already been consumed.")
  private type SubReq[L <: HList] = IsHCons[L]

  @implicitNotFound(msg = "`subContinue` is only possible with exactly one remaining fan-out sub-stream unconsumed!")
  private type SubContinueReq0[L <: HList] = IsSingle[L]
  @implicitNotFound(msg = "`subContinue` is only possible w/o any previous fan-in sub-streams! Here you have: ${L}.")
  private type SubContinueReq1[L <: HList] = IsHNil[L]

  @implicitNotFound(msg = "Cannot `continue` here! You still have at least one unconsumed fan-out sub-stream.")
  private type ContinueReq0[L <: HList] = IsHNil[L]
  @implicitNotFound(msg = "Continuation is only possible with exactly one open fan-in sub-stream!")
  private type ContinueReq1[L <: HList] = IsSingle[L]

  @implicitNotFound(msg = "`end` is only possible without any previous fan-in sub-streams! Here you have: ${L}.")
  private type EndReq0[L <: HList] = IsHNil[L]
  @implicitNotFound(msg = "Cannot `end` here! You still have at least one unconsumed fan-out sub-stream.")
  private type EndReq1[L <: HList] = IsHNil[L]

  @implicitNotFound(msg = "`via` is only possible here without any previous fan-in sub-streams! Here you have: ${L}.")
  private type ViaContinueReq[L <: HList] = IsHNil[L]

  @implicitNotFound(msg = "Cannot assemble sum type `${T}` from `${L}`.")
  sealed abstract class Summable[T, L <: HList] {
    type CP <: Coproduct
    def tpc: ToCoproduct.Aux[L, CP]
    def gen: Generic.Aux[T, CP]
  }
  object Summable {
    implicit def apply[T, L <: HList, C <: Coproduct](implicit _tpc: ToCoproduct.Aux[L, C],
                                                      _gen: Generic.Aux[T, C]): Summable[T, L] { type CP = C } =
      new Summable[T, L] {
        type CP = C
        def tpc = _tpc
        def gen = _gen
      }
  }

  private def attachNop(base: Inport): Inport = {
    val stage = new NopStage
    base.subscribe()(stage)
    stage
  }
}
