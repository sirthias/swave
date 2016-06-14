/*
 * Copyright © 2016 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

final class Stream[+A](private[core] val inport: Inport) extends AnyVal with StreamOps[A @uV] {
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

  def drainTo[R](drain: Drain[A, R])(implicit env: StreamEnv, ev: TypeLogic.TryFlatten[R]): ev.Out =
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
    fromIterator(Iterator.continually(elem)).named("Stream.continually")

  def empty[T]: Stream[T] =
    fromIterator(Iterator.empty)

  def emptyFrom[T](future: Future[Unit]): Stream[T] =
    ???

  def failing[T](cause: Throwable): Stream[T] =
    new Stream(new FailingSourceStage(cause))

  def from(start: Int, step: Int = 1): Stream[Int] =
    fromIterator(Iterator.from(start, step)).named("Stream.from")

  def fromIterable[T](value: Iterable[T]): Stream[T] =
    fromIterator(value.iterator).named("Stream.fromIterable")

  def fromIterator[T](value: Iterator[T]): Stream[T] =
    new Stream(new IteratorStage(value.asInstanceOf[Iterator[AnyRef]]))

  def fromPublisher[T](publisher: Publisher[T]): Stream[T] =
    new Stream(new FromPublisherStage(publisher.asInstanceOf[Publisher[AnyRef]]))

  def iterate[T](start: T)(f: T ⇒ T): Stream[T] =
    fromIterator(Iterator.iterate(start)(f)).named("Stream.iterate")

  def one[T](element: T): Stream[T] =
    fromIterator(Iterator.single(element)).named("Stream.one")

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
