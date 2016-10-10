/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
  * The code in this file is only slightly adapted from a previous version, which
  * is licensed under the Apache License 2.0 and bears this copyright notice:
  *
  *     Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
  *
  * So most credit for this work should go to the original authors.
  */
package swave.core.util

import scala.util.control.{NoStackTrace, NonFatal}
import scala.util.{Failure, Success, Try}
import scala.collection.generic.CanBuildFrom
import scala.concurrent.duration.Duration
import scala.concurrent._

/**
  * Provides alternative implementations of the basic transformation operations defined on [[Future]],
  * which try to avoid scheduling to an [[ExecutionContext]] if possible, i.e. if the given future
  * value is already present.
  */
private[swave] class FastFuture[A](val future: Future[A]) extends AnyVal {
  import FastFuture._

  def map[B](f: A ⇒ B)(implicit ec: ExecutionContext): Future[B] =
    transformWith(a ⇒ FastFuture.successful(f(a)), FastFuture.failed)

  def flatMap[B](f: A ⇒ Future[B])(implicit ec: ExecutionContext): Future[B] =
    transformWith(f, FastFuture.failed)

  def filter(pred: A ⇒ Boolean)(implicit executor: ExecutionContext): Future[A] =
    flatMap { r ⇒
      if (pred(r)) future else throw FilterPredicateFailed
    }

  def foreach(f: A ⇒ Unit)(implicit ec: ExecutionContext): Unit = { map(f); () }

  def transformWith[B](f: Try[A] ⇒ Future[B])(implicit executor: ExecutionContext): Future[B] =
    future match {
      case x: Impl[A] ⇒
        try f(x.valueTry)
        catch { case NonFatal(e) ⇒ ErrorFuture(e) }
      case _ ⇒
        future.value match {
          case None ⇒
            val p = Promise[B]()
            future.onComplete(
              x ⇒
                p.completeWith(try f(x)
                catch { case NonFatal(e) ⇒ ErrorFuture(e) }))
            p.future
          case Some(x) ⇒
            try f(x)
            catch { case NonFatal(e) ⇒ ErrorFuture(e) }
        }
    }

  def transformWith[B](s: A ⇒ Future[B], f: Throwable ⇒ Future[B])(implicit executor: ExecutionContext): Future[B] = {
    def strictTransform[T](x: T, f: T ⇒ Future[B]) =
      try f(x)
      catch { case NonFatal(e) ⇒ ErrorFuture(e) }

    future match {
      case FulfilledFuture(a) ⇒ strictTransform(a, s)
      case ErrorFuture(e)     ⇒ strictTransform(e, f)
      case _ ⇒
        future.value match {
          case None ⇒
            val p = Promise[B]()
            future.onComplete {
              case Success(a) ⇒ p completeWith strictTransform(a, s)
              case Failure(e) ⇒ p completeWith strictTransform(e, f)
            }
            p.future
          case Some(Success(a)) ⇒ strictTransform(a, s)
          case Some(Failure(e)) ⇒ strictTransform(e, f)
        }
    }
  }

  def recover[B >: A](pf: PartialFunction[Throwable, B])(implicit ec: ExecutionContext): Future[B] =
    transformWith(FastFuture.successful, t ⇒ if (pf isDefinedAt t) FastFuture.successful(pf(t)) else future)

  def recoverWith[B >: A](pf: PartialFunction[Throwable, Future[B]])(implicit ec: ExecutionContext): Future[B] =
    transformWith(FastFuture.successful, t ⇒ pf.applyOrElse(t, (_: Throwable) ⇒ future))

  def directMap[B](f: A ⇒ B): Future[B] =
    new Future[B] {
      def isCompleted = future.isCompleted
      def onComplete[U](g: Try[B] ⇒ U)(implicit executor: ExecutionContext): Unit =
        future.onComplete(t ⇒ g(t map f))
      def value: Option[Try[B]]                                         = future.value.map(_ map f)
      def result(atMost: Duration)(implicit permit: CanAwait): B        = f(future.result(atMost))
      def ready(atMost: Duration)(implicit permit: CanAwait): this.type = { future.ready(atMost); this }
    }
}

private[swave] object FastFuture {
  def apply[T](value: Try[T]): Future[T] = value match {
    case Success(t) ⇒ FulfilledFuture(t)
    case Failure(e) ⇒ ErrorFuture(e)
  }
  private[this] val _successful: Any ⇒ Future[Any] = FulfilledFuture.apply
  def successful[T]: T ⇒ Future[T]                 = _successful.asInstanceOf[T ⇒ Future[T]]
  val failed: Throwable ⇒ Future[Nothing]          = ErrorFuture.apply

  sealed abstract class Impl[+A](val valueTry: Try[A]) extends Future[A] {
    final def value                                              = Some(valueTry)
    final def isCompleted                                        = true
    final def ready(atMost: Duration)(implicit permit: CanAwait) = this
  }

  private final case class FulfilledFuture[+A](a: A) extends Impl[A](Success(a)) {
    def onComplete[U](f: Try[A] ⇒ U)(implicit ec: ExecutionContext) = Future.successful(a).onComplete(f)
    def result(atMost: Duration)(implicit permit: CanAwait)         = a
  }
  private final case class ErrorFuture(error: Throwable) extends Impl[Nothing](Failure(error)) {
    def onComplete[U](f: Try[Nothing] ⇒ U)(implicit ec: ExecutionContext) = Future.failed(error).onComplete(f)
    def result(atMost: Duration)(implicit permit: CanAwait)               = throw error
  }

  def sequence[T, M[_] <: TraversableOnce[_]](in: M[Future[T]])(implicit cbf: CanBuildFrom[M[Future[T]], T, M[T]],
                                                                ec: ExecutionContext): Future[M[T]] =
    in.foldLeft(successful(cbf(in))) { (fr, fa) ⇒
        for {
          r ← fr.fast
          a ← fa.asInstanceOf[Future[T]].fast
        } yield r += a
      }
      .fast
      .map(_.result())

  def fold[T, R](futures: TraversableOnce[Future[T]])(zero: R)(f: (R, T) ⇒ R)(
      implicit ec: ExecutionContext): Future[R] =
    if (futures.isEmpty) successful(zero)
    else sequence(futures).fast.map(_.foldLeft(zero)(f))

  def reduce[T, R >: T](futures: TraversableOnce[Future[T]])(op: (R, T) ⇒ R)(
      implicit ec: ExecutionContext): Future[R] =
    if (futures.isEmpty) failed(new NoSuchElementException("reduce attempted on empty collection"))
    else sequence(futures).fast.map(_ reduceLeft op)

  def traverse[A, B, M[_] <: TraversableOnce[_]](in: M[A])(
      fn: A ⇒ Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]], ec: ExecutionContext): Future[M[B]] =
    in.foldLeft(successful(cbf(in))) { (fr, a) ⇒
        val fb = fn(a.asInstanceOf[A])
        for {
          r ← fr.fast
          b ← fb.fast
        } yield r += b
      }
      .fast
      .map(_.result())

  case object FilterPredicateFailed extends NoSuchElementException with NoStackTrace
}
