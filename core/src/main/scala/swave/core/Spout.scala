/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import org.reactivestreams.{Publisher, Subscriber}
import scala.annotation.unchecked.{uncheckedVariance => uV}
import scala.collection.generic.CanBuildFrom
import scala.util.{Failure, Success, Try}
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import shapeless._
import swave.core.impl.util.{InportList, RingBuffer}
import swave.core.impl.{Inport, ModuleImpl, TypeLogic}
import swave.core.impl.stages.StageImpl
import swave.core.impl.stages.spout._
import swave.core.util._

/**
  * A `Spout` is a streaming component with no input port and a single output port.
  * As such it serves as a "point of entry" into a stream graph for data elements from other sources
  * (e.g. from memory, disk or the network).
  *
  * @tparam A the type of the data elements produced
  */
final class Spout[+A](private[swave] val inport: Inport) extends AnyVal with StreamOps[A @uV] {
  type Repr[T] = Spout[T]

  protected def base: Inport           = inport
  protected def wrap: Inport ⇒ Repr[_] = Spout.wrap
  protected[core] def append[B](stage: StageImpl): Spout[B] = {
    inport.subscribe()(stage)
    new Spout(stage)
  }

  /**
    * The [[Stage]] of this spout.
    */
  def stage: Stage = inport.stageImpl

  /**
    * Returns this [[Spout]] instance.
    */
  def identity: Spout[A] = this.asInstanceOf[Spout[A]]

  /**
    * Attaches the given [[Drain]] to close this part of the stream graph.
    */
  def to[R](drain: Drain[A, R]): StreamGraph[R] =
    new StreamGraph(inport.stageImpl, drain.consume(this))

  /**
    * Attaches the given [[Pipe]] and returns a transformed [[Spout]].
    */
  def via[B](pipe: A =>> B): Spout[B] = pipe.transform(this)

  /**
    * Attaches the given [[Module]] and returns the resulting structure.
    */
  def via[P <: HList, R, Out](joined: Module.TypeLogic.Joined[A :: HNil, P, R])(
      implicit vr: TypeLogic.ViaResult[P, StreamGraph[R], Spout, Out]): Out = {
    val out = ModuleImpl(joined.module)(InportList(inport))
    val result = vr.id match {
      case 0 ⇒ new StreamGraph(inport.stageImpl, out)
      case 1 ⇒ new Spout(out.asInstanceOf[InportList].in)
      case 2 ⇒ new StreamOps.FanIn(out.asInstanceOf[InportList], wrap)
    }
    result.asInstanceOf[Out]
  }

  /**
    * Attaches the given [[Drain]] and immediately starts the stream.
    */
  def drainTo[R](drain: Drain[A, R])(implicit env: StreamEnv, ev: TypeLogic.ToTryOrFuture[R]): ev.Out =
    to(drain).run().result

  /**
    * Attaches a [[Drain]] which executes the given callback for all stream elements
    * and immediately starts the stream.
    */
  def foreach(callback: A ⇒ Unit)(implicit env: StreamEnv): Future[Unit] =
    drainTo(Drain.foreach(callback))

  /**
    * Attaches a [[Drain]] which collects all incoming elements into a container type and immediately starts the stream.
    * If the stream produces more than `limit` elements it will be error-terminated with
    * a [[StreamLimitExceeded]] exception.
    */
  def drainToSeq[M[+ _]](limit: Int)(implicit env: StreamEnv, cbf: CanBuildFrom[M[A], A, M[A @uV]]): Future[M[A]] =
    drainTo(Drain.generalSeq[M, A](limit))

  /**
    * Attaches a [[Drain]] which collects all incoming elements into a [[List]] and immediately starts the stream.
    * If the stream produces more than `limit` elements it will be error-terminated with
    * a [[StreamLimitExceeded]] exception.
    */
  def drainToList(limit: Int)(implicit env: StreamEnv): Future[List[A]] =
    drainToSeq[List](limit)

  /**
    * Attaches a [[Drain]] which collects all incoming elements into a [[Vector]] and immediately starts the stream.
    * If the stream produces more than `limit` elements it will be error-terminated with
    * a [[StreamLimitExceeded]] exception.
    */
  def drainToVector(limit: Int)(implicit env: StreamEnv): Future[Vector[A]] =
    drainToSeq[Vector](limit)

  /**
    * Attaches a [[Drain]] which produces the first element as result and immediately starts the stream.
    */
  def drainToHead()(implicit env: StreamEnv): Future[A] =
    drainTo(Drain.head[A])

  /**
    * Attaches a [[Drain]] which drops all elements produced by the stream.
    */
  def drainToBlackHole()(implicit env: StreamEnv): Future[Unit] =
    drainTo(Drain.ignore)

  /**
    * Attaches a [[Drain]] which applies the given folding function across all elements produced by the stream.
    * The stream is immediately started.
    */
  def drainFolding[R](zero: R)(f: (R, A) ⇒ R)(implicit env: StreamEnv): Future[R] =
    drainTo(Drain.fold(zero)(f))

  /**
    * Attaches a [[Drain]] which produces a single string representation of all stream elements.
    * If the stream produces more than `limit` elements it will be error-terminated with
    * a [[StreamLimitExceeded]] exception.
    * The stream is immediately started.
    */
  def drainToMkString(limit: Int, sep: String = "")(implicit env: StreamEnv): Future[String] =
    drainToMkString(limit, "", sep, "")

  /**
    * Attaches a [[Drain]] which produces a single string representation of all stream elements.
    * If the stream produces more than `limit` elements it will be error-terminated with
    * a [[StreamLimitExceeded]] exception.
    * The stream is immediately started.
    */
  def drainToMkString(limit: Int, start: String, sep: String, end: String)(implicit env: StreamEnv): Future[String] =
    drainTo(Drain.mkString(limit, start, sep, end))

  /**
    * Explicitly attaches the given name to this [[Spout]].
    */
  def named(name: String): Spout[A] = {
    Module.ID(name).addBoundary(Module.Boundary.InnerExit(inport.stageImpl))
    this
  }
}

object Spout {

  /**
    * Turns the given value into a [[Spout]] provided an implicit [[Streamable]] instance can be
    * found or constructed for it.
    */
  def apply[T](value: T)(implicit ev: Streamable[T]): Spout[ev.Out] = ev(value)

  /**
    * A [[Spout]] which produces the given elements.
    */
  def apply[T](first: T, second: T, more: T*): Spout[T] =
    fromIterator(Iterator.single(first) ++ Iterator.single(second) ++ more.iterator) named "Spout.apply"

  /**
    * A [[Spout]] and an associated [[Subscriber]].
    * Both are directly linked to each other and thereby allow for connecting *swave* with other
    * Reactive Streams implementations.
    *
    * The [[Spout]] produces all elements received by the [[Subscriber]] and
    * the [[Subscriber]] forwards all request and cancel signals received by the [[Spout]].
    *
    * The returned [[Spout]] instance cannot run synchronously.
    */
  def withSubscriber[T]: (Spout[T], Subscriber[T]) = {
    val stage = new SubscriberSpoutStage
    new Spout[T](stage) → stage.subscriber.asInstanceOf[Subscriber[T]]
  }

  /**
    * A [[Spout]] which will evaluate the given call-by-name argument to produce an infinite stream
    * of data elements.
    *
    * CAUTION: The call-by-name argument might be executed from another thread if the stream is asynchronous!
    */
  def continually[T](elem: ⇒ T): Spout[T] =
    fromIterator(Iterator.continually(elem)) named "Spout.continually"

  /**
    * A [[Spout]] which never produces any elements but completes immediately.
    */
  def empty[T]: Spout[T] =
    fromIterator(Iterator.empty)

  /**
    * A [[Spout]] which never produces any elements but terminates when the given future is fulfilled.
    */
  def emptyFrom[T](future: Future[Unit]): Spout[T] =
    fromFuture(future).drop(1).named("Spout.emptyFrom").asInstanceOf[Spout[T]]

  /**
    * A [[Spout]] which never produces any elements but terminates with the given error.
    * If `eager` is true the error is produced immediately after stream start,
    * otherwise the error is delayed until the first request signal is received from downstream.
    */
  def failing[T](cause: Throwable, eager: Boolean = true): Spout[T] =
    new Spout(new FailingSpoutStage(cause, eager))

  /**
    * A [[Spout]] which produces an infinite stream of `Int` elements starting
    * with the given `start` and increments of `step`.
    */
  def ints(from: Int, step: Int = 1): Spout[Int] =
    fromIterator(Iterator.from(from, step)) named "Spout.ints"

  /**
    * A [[Spout]] which produces an infinite stream of `Long` elements starting
    * with the given `start` and increments of `step`.
    */
  def longs(from: Long, step: Long = 1): Spout[Long] =
    iterate(from)(_ + step) named "Spout.longs"

  /**
    * A [[Spout]] which produces an infinite stream of `Double` elements starting
    * with the given `start` and increments of `step`.
    */
  def doubles(from: Double, step: Double): Spout[Double] =
    iterate(from)(_ + step) named "Spout.doubles"

  /**
    * A [[Spout]] which produces either one or zero elements when the given [[Future]] is
    * completed. If the [[Future]] is completed with an error no element is produced and the stream
    * is failed with the exception in the future.
    *
    * The returned [[Spout]] instance cannot run synchronously.
    */
  def fromFuture[T](future: Future[T]): Spout[T] =
    new Spout(new FutureSpoutStage(future.asInstanceOf[Future[AnyRef]]))

  /**
    * A [[Spout]] which produces the same elements as the given [[Iterable]].
    */
  def fromIterable[T](iterable: Iterable[T]): Spout[T] =
    fromIterator(iterable.iterator) named "Spout.fromIterable"

  /**
    * A [[Spout]] which produces the same elements as the given [[Iterator]].
    *
    * CAUTION: `iterator` might be drained from another thread if the stream is asynchronous!
    */
  def fromIterator[T](iterator: Iterator[T]): Spout[T] =
    new Spout(new IteratorSpoutStage(iterator.asInstanceOf[Iterator[AnyRef]]))

  /**
    * A [[Spout]] which produces either one or zero elements depending on the given [[Option]] instance.
    */
  def fromOption[T](option: Option[T]): Spout[T] =
    (if (option eq None) empty else one(option.get)) named "Spout.fromOption"

  /**
    * A [[Spout]] which produces the same elements as the given [[Publisher]].
    *
    * The returned [[Spout]] instance cannot run synchronously.
    */
  def fromPublisher[T](publisher: Publisher[T]): Spout[T] =
    new Spout(new PublisherSpoutStage(publisher.asInstanceOf[Publisher[AnyRef]]))

  /**
    * A [[Spout]] which produces either one or zero elements depending on the given [[Try]] instance.
    */
  def fromTry[T](value: Try[T]): Spout[T] =
    (value match { case Success(x) ⇒ one(x); case Failure(e) ⇒ failing(e) }) named "Spout.fromTry"

  /**
    * A [[Spout]] which will iterate via the given function to produce an infinite stream
    * of data elements.
    *
    * CAUTION: `f` will be called from another thread if the stream is asynchronous!
    */
  def iterate[T](start: T)(f: T ⇒ T): Spout[T] =
    fromIterator(Iterator.iterate(start)(f)) named "Spout.iterate"

  /**
    * A [[Spout]] which produces the same elements as the [[Spout]] instance returned by the given
    * function. The given function is only executed when the outer stream is started.
    *
    * If the outer stream is synchronous the [[Spout]] returned by `onStart` must be able to run synchronously
    * as well. If it doesn't the stream will fail with an [[IllegalAsyncBoundaryException]].
    */
  def lazyStart[T](onStart: () ⇒ Spout[T]): Spout[T] =
    new Spout(new LazyStartSpoutStage(onStart.asInstanceOf[() ⇒ Spout[AnyRef]]))

  /**
    * A [[Spout]] which produces only the given element.
    */
  def one[T](element: T): Spout[T] =
    fromIterator(Iterator single element) named "Spout.one"

  /**
    * Convenience constructor for [[PushSpout]] instances.
    */
  def push[T](initialBufferSize: Int,
              maxBufferSize: Int,
              growByInitialSize: Boolean = false,
              notifyOnDequeued: (PushSpout[T], Int) ⇒ Unit = dropFunc2,
              notifyOnCancel: PushSpout[T] ⇒ Unit = dropFunc): PushSpout[T] =
    PushSpout(initialBufferSize, maxBufferSize, growByInitialSize, notifyOnDequeued, notifyOnCancel)

  /**
    * A [[Spout]] which produced an infinite stream of identical elements.
    */
  def repeat[T](element: T): Spout[T] =
    new Spout(new RepeatSpoutStage(element.asInstanceOf[AnyRef]))

  /**
    * A [[Spout]] which produces the given element once per given interval.
    *
    * The returned [[Spout]] instance cannot run synchronously.
    */
  def tick[T](value: T, interval: FiniteDuration): Spout[T] =
    tick(value, 1, interval)

  /**
    * A [[Spout]] which produces the given element `elements` times per given interval.
    *
    * The returned [[Spout]] instance cannot run synchronously.
    */
  def tick[T](value: T, elements: Int, per: FiniteDuration): Spout[T] =
    repeat(value).throttle(elements, per)

  /**
    * A `Spout` that unfolds a "state" instance of type `S` into
    * the subsequent states and output elements of type `T`.
    *
    * For example, all the Fibonacci numbers under 1M:
    *
    * {{{
    *   Spout.unfold(0 → 1) {
    *    case (a, b) if b > 1000000 ⇒ Spout.Unfolding.EmitFinal(a)
    *    case (a, b) ⇒ Spout.Unfolding.Emit(a, b → (a + b))
    *   }
    * }}}
    *
    * CAUTION: `f` will be called from another thread if the stream is asynchronous!
    */
  def unfold[S, T](s: S)(f: S ⇒ Unfolding[S, T]): Spout[T] =
    new Spout(new UnfoldSpoutStage(s.asInstanceOf[AnyRef], f.asInstanceOf[AnyRef ⇒ Unfolding[AnyRef, AnyRef]]))

  /**
    * Same as [[unfold]], but asynchronous.
    *
    * The returned [[Spout]] instance cannot run synchronously.
    *
    * CAUTION: `f` will be called from another thread!
    */
  def unfoldAsync[S, T](s: S)(f: S ⇒ Future[Unfolding[S, T]]): Spout[T] =
    new Spout(
      new UnfoldAsyncSpoutStage(s.asInstanceOf[AnyRef], f.asInstanceOf[AnyRef ⇒ Future[Unfolding[AnyRef, AnyRef]]]))

  /**
    * Simple helper ADT for [[unfold]].
    */
  sealed abstract class Unfolding[+S, +T]
  object Unfolding {
    final case class Emit[S, T](elem: T, next: S) extends Unfolding[S, T]
    final case class EmitFinal[T](elem: T)        extends Unfolding[Nothing, T]
    case object Complete                          extends Unfolding[Nothing, Nothing]
  }

  /////////////////////////// INTERNAL /////////////////////////////////

  // CAUTION: [[RingBuffer]] is not thread-safe, so don't try to apply concurrent updates if the stream is async!
  private[swave] def fromRingBuffer[T](buffer: RingBuffer[T]): Spout[T] =
    new Spout(new RingBufferSpoutStage(buffer.asInstanceOf[RingBuffer[AnyRef]]))

  private val wrap: Inport ⇒ Spout[_] = new Spout(_)
}
