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

import org.reactivestreams.Subscriber
import scala.annotation.unchecked.{ uncheckedVariance ⇒ uV }
import scala.collection.generic.CanBuildFrom
import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, FiniteDuration }
import shapeless._
import swave.core.impl.stages.PipeStage
import swave.core.impl.{ InportList, TypeLogic, Inport }
import swave.core.impl.stages.source._

final class Stream[+A](private[core] val inport: Inport) extends AnyVal with StreamOps[A @uV] {
  type Repr[T] = Stream[T]

  protected def base: Inport = inport
  protected def wrap: Inport ⇒ Repr[_] = Stream.wrap
  protected[core] def append[B](stage: PipeStage): Stream[B] = {
    inport.subscribe()(stage)
    new Stream(stage)
  }

  def pipeElem: PipeElem = inport.pipeElem

  def identity: Stream[A] = this.asInstanceOf[Stream[A]]

  def to[R](drain: Drain[A, R]): RunnablePiping[R] =
    new RunnablePiping(drain.outport, drain.consume(this))

  def via[B](pipe: A =>> B): Stream[B] = pipe.transform(this)

  def via[P <: HList, R, Out](joined: Module.Joined[A :: HNil, P, R])(
    implicit
    vr: TypeLogic.ViaResult[P, RunnablePiping[R], Stream, Out]): Out = {
    val out = joined.module(InportList(inport))
    val result = vr.id match {
      case 0 ⇒ new RunnablePiping(inport, out)
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
}

object Stream {
  def apply[T](value: T)(implicit ev: Streamable[T]): Stream[ev.Out] = ev(value)

  def apply[T](first: T, second: T, more: T*): Stream[T] = apply(first :: second :: more.toList)

  def asSubscriber[T]: (Subscriber[T], Stream[T]) = ???

  def continually[T](elem: ⇒ T): Stream[T] = new Stream(new ContinuallyStage((elem _).asInstanceOf[() ⇒ AnyRef]))

  def empty[T]: Stream[T] = ???

  def emptyFrom[T](future: Future[Unit]): Stream[T] = ???

  def failing[T](cause: Throwable): Stream[T] = ???

  def from(start: Int): Stream[Int] = from(start, 1)

  def from(start: Int, step: Int): Stream[Int] = ???

  def fromIterable[T](value: Iterable[T]): Stream[T] = new Stream(new IterableStage(value.asInstanceOf[Iterable[AnyRef]]))

  def fromIterator[T](value: Iterator[T]): Stream[T] = new Stream(new IteratorStage(value.asInstanceOf[Iterator[AnyRef]]))

  def iterate[T](start: T)(f: T ⇒ T): Stream[T] = ???

  def one[T](element: T): Stream[T] = new Stream(new OneElementStage(element.asInstanceOf[AnyRef]))

  def repeat[T](element: T): Stream[T] = new Stream(new RepeatStage(element.asInstanceOf[AnyRef]))

  def tick[T](value: T, interval: FiniteDuration): Stream[T] = tick(value, Duration.Zero, interval)

  def tick[T](value: T, initialDelay: FiniteDuration, interval: FiniteDuration): Stream[T] = ???

  private val wrap: Inport ⇒ Stream[_] = new Stream(_)
}
