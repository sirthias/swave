/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import org.reactivestreams.{ Publisher, Subscriber }
import scala.annotation.unchecked.{ uncheckedVariance ⇒ uV }
import scala.collection.generic.CanBuildFrom
import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, FiniteDuration }
import shapeless._
import swave.core.impl.{ ModuleMarker, InportList, TypeLogic, Inport }
import swave.core.impl.stages.Stage
import swave.core.impl.stages.source._

final class Stream[+A](private[swave] val inport: Inport) extends AnyVal with StreamOps[A @uV] {
  type Repr[T] = Stream[T]

  protected def base: Inport = inport
  protected def wrap: Inport ⇒ Repr[_] = Stream.wrap
  protected[core] def append[B](stage: Stage): Stream[B] = {
    inport.subscribe()(stage)
    new Stream(stage)
  }

  def pipeElem: PipeElem = inport.pipeElem

  def identity: Stream[A] = this.asInstanceOf[Stream[A]]

  def to[R](drain: Drain[A, R]): Piping[R] =
    new Piping(inport, drain.consume(this))

  def via[B](pipe: A =>> B): Stream[B] = pipe.transform(this)

  def via[P <: HList, R, Out](joined: Module.Joined[A :: HNil, P, R])(
    implicit
    vr: TypeLogic.ViaResult[P, Piping[R], Stream, Out]): Out = {
    val out = joined.module(InportList(inport))
    val result = vr.id match {
      case 0 ⇒ new Piping(inport, out)
      case 1 ⇒ new Stream(out.asInstanceOf[InportList].in)
      case 2 ⇒ new StreamOps.FanIn(out.asInstanceOf[InportList], wrap)
    }
    result.asInstanceOf[Out]
  }

  def foreach(callback: A ⇒ Unit)(implicit env: StreamEnv): Future[Unit] =
    drainTo(Drain.foreach(callback))

  def drainTo[R](drain: Drain[A, R])(implicit env: StreamEnv, ev: TypeLogic.ToTryOrFuture[R]): ev.Out =
    to(drain).run()

  def drainToSeq[M[+_]](limit: Long)(implicit env: StreamEnv, cbf: CanBuildFrom[M[A], A, M[A @uV]]): Future[M[A]] =
    drainTo(Drain.generalSeq[M, A](limit))

  def drainToList(limit: Long)(implicit env: StreamEnv): Future[List[A]] =
    drainToSeq[List](limit)

  def drainToVector(limit: Long)(implicit env: StreamEnv): Future[Vector[A]] =
    drainToSeq[Vector](limit)

  def named(name: String): Stream[A] = {
    val marker = new ModuleMarker(name)
    marker.markExit(inport)
    this
  }
}

object Stream {
  def apply[T](value: T)(implicit ev: Streamable[T]): Stream[ev.Out] = ev(value)

  def apply[T](first: T, second: T, more: T*): Stream[T] =
    apply(first :: second :: more.toList)

  def withSubscriber[T]: (Stream[T], Subscriber[T]) = {
    val stage = new SubscriberSourceStage
    new Stream[T](stage) → stage.subscriber.asInstanceOf[Subscriber[T]]
  }

  def continually[T](elem: ⇒ T): Stream[T] =
    fromIterator(Iterator.continually(elem)) named "Stream.continually"

  def empty[T]: Stream[T] =
    fromIterator(Iterator.empty)

  def emptyFrom[T](future: Future[Unit]): Stream[T] =
    ???

  def failing[T](cause: Throwable): Stream[T] =
    new Stream(new FailingSourceStage(cause))

  def from(start: Int, step: Int = 1): Stream[Int] =
    fromIterator(Iterator.from(start, step)) named "Stream.from"

  def fromIterable[T](value: Iterable[T]): Stream[T] =
    fromIterator(value.iterator) named "Stream.fromIterable"

  def fromIterator[T](value: Iterator[T]): Stream[T] =
    new Stream(new IteratorStage(value.asInstanceOf[Iterator[AnyRef]]))

  def fromOption[T](value: Option[T]): Stream[T] =
    (if (value.isEmpty) empty else one(value.get)) named "Stream.fromOption"

  def fromPublisher[T](publisher: Publisher[T]): Stream[T] =
    new Stream(new FromPublisherStage(publisher.asInstanceOf[Publisher[AnyRef]]))

  def iterate[T](start: T)(f: T ⇒ T): Stream[T] =
    fromIterator(Iterator.iterate(start)(f)) named "Stream.iterate"

  def lazyStart[T](onStart: () ⇒ Stream[T], subscriptionTimeout: Duration = Duration.Undefined): Stream[T] =
    new Stream(new LazyStartSourceStage(onStart.asInstanceOf[() ⇒ Stream[AnyRef]], subscriptionTimeout))

  def one[T](element: T): Stream[T] =
    fromIterator(Iterator.single(element)) named "Stream.one"

  def repeat[T](element: T): Stream[T] =
    new Stream(new RepeatStage(element.asInstanceOf[AnyRef]))

  def tick[T](value: T, interval: FiniteDuration): Stream[T] =
    tick(value, Duration.Zero, interval)

  def tick[T](value: T, initialDelay: FiniteDuration, interval: FiniteDuration): Stream[T] =
    ???

  /**
   * A `Stream` that unfolds a "state" instance of type `S` into
   * the subsequent states and output elements of type `T`.
   *
   * For example, all the Fibonacci numbers under 1M:
   *
   * {{{
   *   Stream.unfold(0 → 1) {
   *    case (a, b) if b > 1000000 ⇒ Stream.Unfolding.FinalElem(a)
   *    case (a, b) ⇒ Stream.Unfolding.Iteration(a, b → (a + b))
   *   }
   * }}}
   */
  def unfold[S, T](s: S)(f: S ⇒ Unfolding[S, T]): Stream[T] =
    ???

  /**
   * Same as [[unfold]], but asynchronous.
   */
  def unfoldAsync[S, T](s: S)(f: S ⇒ Future[Unfolding[S, T]]): Stream[T] =
    ???

  sealed abstract class Unfolding[+S, +T]
  object Unfolding {
    final case class Iteration[S, T](elem: T, next: S) extends Unfolding[S, T]
    final case class FinalElem[T](elem: T) extends Unfolding[Nothing, T]
    case object Done extends Unfolding[Nothing, Nothing]
  }

  private val wrap: Inport ⇒ Stream[_] = new Stream(_)
}
