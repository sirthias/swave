/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import scala.annotation.implicitNotFound
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}
import shapeless._
import shapeless.ops.hlist.Comapped
import swave.core.{Spout, StreamOps}

import scala.util.control.NonFatal

object TypeLogic {

  sealed trait IsHNil[L <: HList]
  object IsHNil {
    implicit def apply: IsHNil[HNil] = null
  }

  sealed trait IsSingle[L <: HList] {
    type Out
  }
  object IsSingle {
    implicit def apply[T]: IsSingle[T :: HNil] { type Out = T } = null
  }

  trait IsHCons[L <: HList] extends Serializable {
    type H
    type T <: HList
  }
  object IsHCons {
    implicit def apply[H0, T0 <: HList]: IsHCons[H0 :: T0] { type H = H0; type T = T0 } = null
  }

  trait IsHCons2[L <: HList]
  object IsHCons2 {
    implicit def apply[H0, H1, T <: HList]: IsHCons2[H0 :: H1 :: T] = null
  }

  @implicitNotFound(msg = "Argument must be an HList of `Spout[_]`")
  type IsHListOfSpout[L <: HList] = Comapped[L, Spout]

  sealed abstract class ToTryOrFuture[-T] {
    type Out
    def success(value: T): Out
    def failure(error: Throwable): Out
  }
  object ToTryOrFuture extends ToTryOrFuture0 {
    implicit def forFuture[T] =
      new ToTryOrFuture[Future[T]] {
        type Out = Future[T]
        def success(value: Future[T]) = value
        def failure(error: Throwable) = Future.failed(error)
      }
  }
  sealed abstract class ToTryOrFuture0 {
    implicit def forAny[T] =
      new ToTryOrFuture[T] {
        type Out = Try[T]
        def success(value: T)         = Success(value)
        def failure(error: Throwable) = Failure(error)
      }
  }

  sealed abstract class ToFuture[T] {
    type Out
    def apply(t: T): Future[Out]
  }
  object ToFuture extends ToFuture0 {
    implicit def forFuture[T] =
      new ToFuture[Future[T]] {
        type Out = T
        def apply(value: Future[T]) = value
      }
  }
  sealed abstract class ToFuture0 {
    implicit def forAny[T] =
      new ToFuture[T] {
        type Out = T
        def apply(value: T) = Future.successful(value)
      }
  }

  sealed abstract class FlattenFuture[T] {
    type Out
    def apply(promise: Promise[Out])(value: => T): Unit
  }
  object FlattenFuture extends FlattenFuture0 {
    implicit def forFuture[T] =
      new FlattenFuture[Future[T]] {
        type Out = T
        def apply(promise: Promise[T])(value: => Future[T]): Unit =
          try promise.completeWith(value)
          catch {
            case NonFatal(e) => promise.failure(e)
          }
      }
  }
  sealed abstract class FlattenFuture0 {
    implicit def forAny[T] =
      new FlattenFuture[T] {
        type Out = T
        def apply(promise: Promise[T])(value: => T): Unit =
          try promise.success(value)
          catch {
            case NonFatal(e) => promise.failure(e)
          }
      }
  }

  sealed trait Mapped[L <: HList, F[_]] {
    type Out <: HList
  }
  object Mapped {
    type Aux[L <: HList, F[_], Out0 <: HList] = Mapped[L, F] { type Out = Out0 }
    implicit def hnilMapped[F[_]]: Aux[HNil, F, HNil] = null
    implicit def hlistMapped[H, T <: HList, F[_], OutM <: HList](
        implicit mt: Mapped.Aux[T, F, OutM]): Aux[H :: T, F, F[H] :: OutM] = null
  }

  final class HLen[L <: HList](val value: Int) {
    lazy val succ: HLen[HList] = new HLen(value + 1)
  }
  object HLen {
    implicit val hnil: HLen[HNil]                                         = new HLen(0)
    implicit def hlist[H, T <: HList](implicit ev: HLen[T]): HLen[H :: T] = ev.succ.asInstanceOf[HLen[H :: T]]
  }

  sealed trait SelectNonUnit[A, B] {
    type Out
  }
  object SelectNonUnit extends LowPrioSelectNonUnit {
    def doubleUnit: SelectNonUnit[Unit, Unit] { type Out = Unit } = null
  }
  sealed abstract class LowPrioSelectNonUnit {
    def _1[A]: SelectNonUnit[A, Unit] { type Out = A } = null
    def _2[B]: SelectNonUnit[Unit, B] { type Out = B } = null
  }

  sealed trait HLub[L <: HList] {
    type Out
  }
  object HLub {
    type Aux[L <: HList, Out0] = HLub[L] { type Out = Out0 }
    implicit def hsingle[T]: Aux[T :: HNil, T]                                                         = null
    implicit def hlist[H, T <: HList, TO, O](implicit a: Aux[T, TO], u: Lub[H, TO, O]): Aux[H :: T, O] = null
  }

  final class ViaResult[L <: HList, Out0, Repr[_] <: StreamOps[_], Out](val id: Int) extends AnyVal
  object ViaResult extends LowPrioViaResult {
    implicit def _0[Out0, Repr[_] <: StreamOps[_]]: ViaResult[HNil, Out0, Repr, Out0]            = new ViaResult(0)
    implicit def _1[T, Out0, Repr[_] <: StreamOps[_]]: ViaResult[T :: HNil, Out0, Repr, Repr[T]] = new ViaResult(1)
  }
  sealed abstract class LowPrioViaResult {
    implicit def _n[L <: HList, Out0, Repr[_] <: StreamOps[_], U](
        implicit ev: HLub.Aux[L, U]): ViaResult[L, Out0, Repr, Repr[_]#FanIn[L, U]] = new ViaResult(2)
  }
}
