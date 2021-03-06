/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import org.reactivestreams.Publisher
import scala.annotation.implicitNotFound
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try
import swave.core.impl.util.RingBuffer
import swave.core.io.Bytes

@implicitNotFound(
  msg =
    "Don't know how to create a stream from instances of type ${T}. Maybe you'd like to provide an `implicit Streamable[${T}]`?")
//#source-quote
abstract class Streamable[-T] {
  type Out
  def apply(value: T): Spout[Out]
}
//#source-quote

object Streamable {
  type Aux[T, O] = Streamable[T] { type Out = O }

  private val spout =
    new Streamable[Spout[AnyRef]] {
      type Out = AnyRef
      def apply(value: Spout[AnyRef]) = value
    }
  implicit def forSpout[T]: Aux[Spout[T], T] = spout.asInstanceOf[Aux[Spout[T], T]]

  private val option =
    new Streamable[Option[AnyRef]] {
      type Out = AnyRef
      def apply(value: Option[AnyRef]) = Spout.fromOption(value)
    }
  implicit def forOption[T]: Aux[Option[T], T] = option.asInstanceOf[Aux[Option[T], T]]

  private val iterable =
    new Streamable[immutable.Iterable[AnyRef]] {
      type Out = AnyRef
      def apply(value: immutable.Iterable[AnyRef]) = Spout.fromIterable(value)
    }
  implicit def forIterable[T]: Aux[immutable.Iterable[T], T] =
    iterable.asInstanceOf[Aux[immutable.Iterable[T], T]]

  private val iterator =
    new Streamable[Iterator[AnyRef]] {
      type Out = AnyRef
      def apply(value: Iterator[AnyRef]) = Spout.fromIterator(value)
    }
  implicit def forIterator[T]: Aux[Iterator[T], T] = iterator.asInstanceOf[Aux[Iterator[T], T]]

  private val publisher =
    new Streamable[Publisher[AnyRef]] {
      type Out = AnyRef
      def apply(value: Publisher[AnyRef]) = Spout.fromPublisher(value)
    }
  implicit def forPublisher[T]: Aux[Publisher[T], T] =
    publisher.asInstanceOf[Aux[Publisher[T], T]]

  private val ringBuffer =
    new Streamable[RingBuffer[AnyRef]] {
      type Out = AnyRef
      def apply(value: RingBuffer[AnyRef]) = Spout.fromRingBuffer(value)
    }
  private[swave] implicit def forRingBuffer[T]: Aux[RingBuffer[T], T] =
    ringBuffer.asInstanceOf[Aux[RingBuffer[T], T]]

  private val future =
    new Streamable[Future[AnyRef]] {
      type Out = AnyRef
      def apply(value: Future[AnyRef]) = Spout.fromFuture(value)
    }
  implicit def forFuture[T]: Aux[Future[T], T] = future.asInstanceOf[Aux[Future[T], T]]

  private val tryy =
    new Streamable[Try[AnyRef]] {
      type Out = AnyRef
      def apply(value: Try[AnyRef]) = Spout.fromTry(value)
    }
  implicit def forTry[T]: Aux[Try[T], T] = tryy.asInstanceOf[Aux[Try[T], T]]

  implicit def forBytes[T](implicit ev: Bytes[T]): Aux[T, Byte] =
    new Streamable[T] {
      type Out = Byte
      def apply(value: T): Spout[Byte] = Spout.fromIterator(ev.toSeq(value).iterator)
    }

  implicit def lazyStreamable[T, O](implicit ev: Streamable.Aux[T, O]): Aux[() ⇒ T, O] =
    new Streamable[() ⇒ T] {
      type Out = O
      def apply(f: () ⇒ T) = ev(f())
    }
}
