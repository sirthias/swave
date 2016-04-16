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

import org.reactivestreams.Publisher
import scala.annotation.implicitNotFound
import scala.concurrent.Future

@implicitNotFound(msg = "Don't know how to create a stream from instances of type ${T}. Maybe you'd like to provide an `implicit Streamable[${T}]`?")
abstract class Streamable[-T] {
  type Out
  def apply(value: T): Stream[Out]
}

object Streamable {
  type Aux[T, Out0] = Streamable[T] { type Out = Out0 }

  private[core] val stream =
    new Streamable[Stream[AnyRef]] {
      type Out = AnyRef
      def apply(value: Stream[AnyRef]) = value
    }
  implicit def forStream[T]: Aux[Stream[T], T] = stream.asInstanceOf[Aux[Stream[T], T]]

  private[core] val iterable =
    new Streamable[Iterable[AnyRef]] {
      type Out = AnyRef
      def apply(value: Iterable[AnyRef]): Stream[AnyRef] = Stream.fromIterable(value)
    }
  implicit def forIterable[T]: Aux[Iterable[T], T] = iterable.asInstanceOf[Aux[Iterable[T], T]]

  private[core] val iterator =
    new Streamable[Iterator[AnyRef]] {
      type Out = AnyRef
      def apply(value: Iterator[AnyRef]): Stream[AnyRef] = Stream.fromIterator(value)
    }
  implicit def forIterator[T]: Aux[Iterator[T], T] = iterable.asInstanceOf[Aux[Iterator[T], T]]

  private[core] val publisher =
    new Streamable[Publisher[AnyRef]] {
      type Out = AnyRef
      def apply(value: Publisher[AnyRef]): Stream[AnyRef] = ???
    }
  implicit def forPublisher[T]: Aux[Publisher[T], T] = iterable.asInstanceOf[Aux[Publisher[T], T]]

  private[core] val future =
    new Streamable[Future[AnyRef]] {
      type Out = AnyRef
      def apply(value: Future[AnyRef]): Stream[AnyRef] = ???
    }
  implicit def forFuture[T]: Aux[Future[T], T] = future.asInstanceOf[Aux[Future[T], T]]
}
