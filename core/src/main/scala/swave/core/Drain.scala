/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.annotation.tailrec
import scala.collection.generic.CanBuildFrom
import scala.collection.immutable
import scala.concurrent.{ Future, Promise }
import org.reactivestreams.{ Publisher, Subscriber }
import swave.core.impl.{ ModuleMarker, Outport }
import swave.core.impl.stages.drain._

final class Drain[-T, +R] private[swave] (val outport: Outport, val result: R) {

  def pipeElem: PipeElem = outport.pipeElem

  private[core] def consume(stream: Stream[T]): R = {
    stream.inport.subscribe()(outport)
    result
  }

  def capture[P](promise: Promise[P])(implicit capture: Drain.Capture[R, P]): Drain[T, Unit] = {
    capture(result, promise)
    dropResult
  }

  def dropResult: Drain[T, Unit] = new Drain(outport, ())

  def named(name: String): this.type = {
    val marker = new ModuleMarker(name)
    marker.markEntry(outport)
    this
  }

  /**
   * Marks all primary drains in the graph behind this drain's `outport` as
   * "to be run on the dispatcher with the given id".
   * If the `dispatcherId` is empty the default dispatcher will be assigned
   * if no other non-default assignment has previously been made.
   *
   * Note that the graph behind this drain's `outport` must not contain any explicit async boundaries!
   * Otherwise an [[IllegalStateException]] will be thrown.
   */
  def async(dispatcherId: String = ""): Drain[T, R] =
    outport match {
      case stage: DrainStage ⇒
        stage.assignDispatcherId(dispatcherId)
        this

      case x: PipeElem.Basic ⇒
        val assign = new (PipeElem.Basic ⇒ Unit) {
          var visited = Set.empty[PipeElem]
          def _apply(pe: PipeElem.Basic): Unit = apply(pe)
          @tailrec def apply(pe: PipeElem.Basic): Unit =
            if (!visited.contains(pe)) {
              visited += pe
              pe match {
                case _: PipeElem.InOut.AsyncBoundary ⇒ fail()
                case x: PipeElem.InOut               ⇒ { _apply(x.inputElem); apply(x.outputElem) }
                case x: PipeElem.FanIn               ⇒ { x.inputElems.foreach(this); apply(x.outputElem) }
                case x: PipeElem.FanOut              ⇒ { x.outputElems.foreach(this); apply(x.inputElem) }
                case x: DrainStage                   ⇒ x.assignDispatcherId(dispatcherId)
                case _                               ⇒ ()
              }
            }
          def fail() =
            throw new IllegalAsyncBoundaryException(s"Cannot assign dispatcher '$dispatcherId' to drain '$this'. " +
              "The drain's graph contains at least one explicit async boundary.")
        }
        assign(x)
        this

      case _ ⇒ throw new IllegalStateException
    }
}

object Drain {

  def toPublisher[T](fanoutSupport: Boolean = false): Drain[T, Publisher[T]] = {
    if (fanoutSupport) ???
    val stage = new ToPublisherDrainStage
    new Drain(stage, stage.publisher.asInstanceOf[Publisher[T]])
  }

  def cancelling: Drain[Any, Unit] =
    new Drain(new CancellingDrainStage, ())

  def first[T](n: Int): Drain[T, Future[immutable.Seq[T]]] =
    Pipe[T].grouped(n, emitSingleEmpty = true).to(head).named("Drain.first")

  def foreach[T](callback: T ⇒ Unit): Drain[T, Future[Unit]] = {
    val promise = Promise[Unit]()
    new Drain(new ForeachDrainStage(callback.asInstanceOf[AnyRef ⇒ Unit], promise), promise.future)
  }

  def fold[T, R](zero: R)(f: (R, T) ⇒ R): Drain[T, Future[R]] =
    Pipe[T].fold(zero)(f).to(head).named("Drain.fold")

  def fromSubscriber[T](subscriber: Subscriber[T]): Drain[T, Unit] =
    new Drain(new FromSubscriberStage(subscriber.asInstanceOf[Subscriber[AnyRef]]), ())

  def head[T]: Drain[T, Future[T]] = {
    val promise = Promise[AnyRef]()
    new Drain(new HeadDrainStage(promise), promise.future.asInstanceOf[Future[T]])
  }

  def headOption[T]: Drain[T, Future[Option[T]]] = ???

  def ignore: Drain[Any, Future[Unit]] =
    foreach(util.dropFunc).named("Drain.ignore")

  def last[T]: Drain[T, Future[T]] =
    Pipe[T].last.to(head).named("Drain.last")

  def lastOption[T]: Drain[T, Future[Option[T]]] =
    Pipe[T].last.to(headOption).named("Drain.lastOption")

  def parallelForeach[T](parallelism: Int)(f: T ⇒ Unit): Drain[T, Future[Unit]] = ???

  def seq[T](limit: Long): Drain[T, Future[immutable.Seq[T]]] =
    generalSeq[immutable.Seq, T](limit)

  def generalSeq[M[+_], T](limit: Long)(implicit cbf: CanBuildFrom[M[T], T, M[T]]): Drain[T, Future[M[T]]] =
    Pipe[T].limit(limit).groupedTo[M](Integer.MAX_VALUE, emitSingleEmpty = true).to(head).named("Drain.seq")

  sealed abstract class Capture[-R, P] {
    def apply(result: R, promise: Promise[P]): Unit
  }
  object Capture extends Capture0 {
    private[this] val _forFuture =
      new Capture[Future[Any], Any] {
        def apply(result: Future[Any], promise: Promise[Any]): Unit = { promise.completeWith(result); () }
      }
    implicit def forFuture[T, P >: T]: Capture[Future[T], P] = _forFuture.asInstanceOf[Capture[Future[T], P]]
  }
  sealed abstract class Capture0 {
    private[this] val _forAny =
      new Capture[Any, Any] {
        def apply(result: Any, promise: Promise[Any]): Unit = { promise.success(result); () }
      }
    implicit def forAny[T, P >: T]: Capture[T, P] = _forAny.asInstanceOf[Capture[T, P]]
  }
}
