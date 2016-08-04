/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import org.reactivestreams.{ Publisher, Subscriber }
import scala.annotation.unchecked.{ uncheckedVariance ⇒ uV }
import scala.collection.generic.CanBuildFrom
import scala.util.{ Failure, Success, Try }
import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, FiniteDuration }
import shapeless._
import swave.core.impl.util.InportList
import swave.core.impl.{ ModuleImpl, TypeLogic, Inport }
import swave.core.impl.stages.Stage
import swave.core.impl.stages.spout._

final class Spout[+A](private[swave] val inport: Inport) extends AnyVal with StreamOps[A @uV] {
  type Repr[T] = Spout[T]

  protected def base: Inport = inport
  protected def wrap: Inport ⇒ Repr[_] = Spout.wrap
  protected[core] def append[B](stage: Stage): Spout[B] = {
    inport.subscribe()(stage)
    new Spout(stage)
  }

  def pipeElem: PipeElem = inport.pipeElem

  def identity: Spout[A] = this.asInstanceOf[Spout[A]]

  def to[R](drain: Drain[A, R]): Piping[R] =
    new Piping(inport, drain.consume(this))

  def via[B](pipe: A =>> B): Spout[B] = pipe.transform(this)

  def via[P <: HList, R, Out](joined: Module.TypeLogic.Joined[A :: HNil, P, R])(
    implicit
    vr: TypeLogic.ViaResult[P, Piping[R], Spout, Out]): Out = {
    val out = ModuleImpl(joined.module)(InportList(inport))
    val result = vr.id match {
      case 0 ⇒ new Piping(inport, out)
      case 1 ⇒ new Spout(out.asInstanceOf[InportList].in)
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

  def drainToBlackHole()(implicit env: StreamEnv): Future[Unit] =
    drainTo(Drain.ignore)

  def drainFolding[R](zero: R)(f: (R, A) ⇒ R)(implicit env: StreamEnv): Future[R] =
    drainTo(Drain.fold(zero)(f))

  def named(name: String): Spout[A] = {
    Module.ID(name).markAsInnerExit(inport)
    this
  }
}

object Spout {
  def apply[T](value: T)(implicit ev: Streamable[T]): Spout[ev.Out] = ev(value)

  def apply[T](first: T, second: T, more: T*): Spout[T] =
    fromIterator(Iterator.single(first) ++ Iterator.single(second) ++ more.iterator) named "Spout.apply"

  def withSubscriber[T]: (Spout[T], Subscriber[T]) = {
    val stage = new SubscriberSpoutStage
    new Spout[T](stage) → stage.subscriber.asInstanceOf[Subscriber[T]]
  }

  def continually[T](elem: ⇒ T): Spout[T] =
    fromIterator(Iterator.continually(elem)) named "Spout.continually"

  def empty[T]: Spout[T] =
    fromIterator(Iterator.empty)

  def emptyFrom[T](future: Future[Unit]): Spout[T] =
    fromFuture(future).drop(1).named("Spout.emptyFrom").asInstanceOf[Spout[T]]

  def failing[T](cause: Throwable): Spout[T] =
    new Spout(new FailingSpoutStage(cause))

  def from(start: Int, step: Int = 1): Spout[Int] =
    fromIterator(Iterator.from(start, step)) named "Spout.from"

  def fromFuture[T](future: Future[T]): Spout[T] =
    new Spout(new FutureSpoutStage(future.asInstanceOf[Future[AnyRef]]))

  def fromIterable[T](iterable: Iterable[T]): Spout[T] =
    fromIterator(iterable.iterator) named "Spout.fromIterable"

  def fromIterator[T](iterator: Iterator[T]): Spout[T] =
    new Spout(new IteratorSpoutStage(iterator.asInstanceOf[Iterator[AnyRef]]))

  def fromOption[T](option: Option[T]): Spout[T] =
    (if (option.isEmpty) empty else one(option.get)) named "Spout.fromOption"

  def fromPublisher[T](publisher: Publisher[T]): Spout[T] =
    new Spout(new PublisherSpoutStage(publisher.asInstanceOf[Publisher[AnyRef]]))

  def fromTry[T](value: Try[T]): Spout[T] =
    (value match { case Success(x) ⇒ one(x); case Failure(e) ⇒ failing(e) }) named "Spout.fromTry"

  def iterate[T](start: T)(f: T ⇒ T): Spout[T] =
    fromIterator(Iterator.iterate(start)(f)) named "Spout.iterate"

  def lazyStart[T](onStart: () ⇒ Spout[T], subscriptionTimeout: Duration = Duration.Undefined): Spout[T] =
    new Spout(new LazyStartSpoutStage(onStart.asInstanceOf[() ⇒ Spout[AnyRef]], subscriptionTimeout))

  def one[T](element: T): Spout[T] =
    fromIterator(Iterator.single(element)) named "Spout.one"

  def repeat[T](element: T): Spout[T] =
    new Spout(new RepeatSpoutStage(element.asInstanceOf[AnyRef]))

  def tick[T](value: T, interval: FiniteDuration): Spout[T] =
    tick(value, 1, interval)

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
   */
  def unfold[S, T](s: S)(f: S ⇒ Unfolding[S, T]): Spout[T] =
    new Spout(new UnfoldSpoutStage(s.asInstanceOf[AnyRef], f.asInstanceOf[AnyRef ⇒ Unfolding[AnyRef, AnyRef]]))

  /**
   * Same as [[unfold]], but asynchronous.
   */
  def unfoldAsync[S, T](s: S)(f: S ⇒ Future[Unfolding[S, T]]): Spout[T] =
    new Spout(new UnfoldAsyncSpoutStage(s.asInstanceOf[AnyRef], f.asInstanceOf[AnyRef ⇒ Future[Unfolding[AnyRef, AnyRef]]]))

  sealed abstract class Unfolding[+S, +T]
  object Unfolding {
    final case class Emit[S, T](elem: T, next: S) extends Unfolding[S, T]
    final case class EmitFinal[T](elem: T) extends Unfolding[Nothing, T]
    case object Complete extends Unfolding[Nothing, Nothing]
  }

  private val wrap: Inport ⇒ Spout[_] = new Spout(_)
}
