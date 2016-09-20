/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import org.reactivestreams.Publisher
import scala.annotation.implicitNotFound
import scala.concurrent.Future
import scala.util.Try
import swave.core.util.RingBuffer

@implicitNotFound(msg = "Don't know how to create a stream from instances of type ${T}. Maybe you'd like to provide an `implicit Streamable[${T}]`?")
abstract class Streamable[-T] {
  type Out
  def apply(value: T): Spout[Out]
}

object Streamable {
  type Aux[T, Out0] = Streamable[T] { type Out = Out0 }

  private val spout =
    new Streamable[Spout[AnyRef]] {
      type Out = AnyRef
      def apply(value: Spout[AnyRef]) = value
    }
  implicit def forSpout[T]: Aux[Spout[T], T] = spout.asInstanceOf[Aux[Spout[T], T]]

  private val option =
    new Streamable[Option[AnyRef]] {
      type Out = AnyRef
      def apply(value: Option[AnyRef]): Spout[AnyRef] = Spout.fromOption(value)
    }
  implicit def forOption[T]: Aux[Option[T], T] = option.asInstanceOf[Aux[Option[T], T]]

  private val iterable =
    new Streamable[Iterable[AnyRef]] {
      type Out = AnyRef
      def apply(value: Iterable[AnyRef]): Spout[AnyRef] = Spout.fromIterable(value)
    }
  implicit def forIterable[T]: Aux[Iterable[T], T] = iterable.asInstanceOf[Aux[Iterable[T], T]]

  private val iterator =
    new Streamable[Iterator[AnyRef]] {
      type Out = AnyRef
      def apply(value: Iterator[AnyRef]): Spout[AnyRef] = Spout.fromIterator(value)
    }
  implicit def forIterator[T]: Aux[Iterator[T], T] = iterator.asInstanceOf[Aux[Iterator[T], T]]

  private val publisher =
    new Streamable[Publisher[AnyRef]] {
      type Out = AnyRef
      def apply(value: Publisher[AnyRef]): Spout[AnyRef] = Spout.fromPublisher(value)
    }
  implicit def forPublisher[T]: Aux[Publisher[T], T] = publisher.asInstanceOf[Aux[Publisher[T], T]]

  private val ringBuffer =
    new Streamable[RingBuffer[AnyRef]] {
      type Out = AnyRef
      def apply(value: RingBuffer[AnyRef]): Spout[AnyRef] = Spout.fromRingBuffer(value)
    }
  implicit def forRingBuffer[T]: Aux[RingBuffer[T], T] = ringBuffer.asInstanceOf[Aux[RingBuffer[T], T]]

  private val future =
    new Streamable[Future[AnyRef]] {
      type Out = AnyRef
      def apply(value: Future[AnyRef]): Spout[AnyRef] = Spout.fromFuture(value)
    }
  implicit def forFuture[T]: Aux[Future[T], T] = future.asInstanceOf[Aux[Future[T], T]]

  private val tryy =
    new Streamable[Try[AnyRef]] {
      type Out = AnyRef
      def apply(value: Try[AnyRef]): Spout[AnyRef] = Spout.fromTry(value)
    }
  implicit def forTry[T]: Aux[Try[T], T] = tryy.asInstanceOf[Aux[Try[T], T]]

  implicit def lazyStreamable[T](implicit ev: Streamable[T]): Aux[() ⇒ T, ev.Out] =
    new Streamable[() ⇒ T] {
      type Out = ev.Out
      def apply(f: () ⇒ T) = ev(f())
    }
}
