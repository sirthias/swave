/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.annotation.tailrec
import scala.collection.generic.CanBuildFrom
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Promise }
import org.reactivestreams.{ Publisher, Subscriber }
import swave.core.impl.TypeLogic.ToFuture
import swave.core.impl.Outport
import swave.core.impl.stages.drain._

final class Drain[-T, +R] private[swave] (private[swave] val outport: Outport, val result: R) {

  def pipeElem: PipeElem = outport.pipeElem

  private[core] def consume(spout: Spout[T]): R = {
    spout.inport.subscribe()(outport)
    result
  }

  def capture[P](promise: Promise[P])(implicit capture: Drain.Capture[R, P]): Drain[T, Unit] = {
    capture(result, promise)
    dropResult
  }

  def dropResult: Drain[T, Unit] = Drain(outport)

  def mapResult[P](f: R ⇒ P): Drain[T, P] = new Drain(outport, f(result))

  def named(name: String): this.type = {
    Module.ID(name).markAsInnerEntry(outport)
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

      case x: PipeElem ⇒
        val assign = new (PipeElem ⇒ Unit) {
          var visited = Set.empty[PipeElem]
          def _apply(pe: PipeElem): Unit = apply(pe)
          @tailrec def apply(pe: PipeElem): Unit =
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

  def cancelling: Drain[Any, Unit] =
    Drain(new CancellingDrainStage)

  def first[T](n: Int): Drain[T, Future[immutable.Seq[T]]] =
    Pipe[T].grouped(n, emitSingleEmpty = true).to(head) named "Drain.first"

  def foreach[T](callback: T ⇒ Unit): Drain[T, Future[Unit]] = {
    val promise = Promise[Unit]()
    new Drain(new ForeachDrainStage(callback.asInstanceOf[AnyRef ⇒ Unit], promise), promise.future)
  }

  def fold[T, R](zero: R)(f: (R, T) ⇒ R): Drain[T, Future[R]] =
    Pipe[T].fold(zero)(f).to(head) named "Drain.fold"

  def fromSubscriber[T](subscriber: Subscriber[T]): Drain[T, Unit] =
    Drain(new SubscriberDrainStage(subscriber.asInstanceOf[Subscriber[AnyRef]]))

  def generalSeq[M[+_], T](limit: Long)(implicit cbf: CanBuildFrom[M[T], T, M[T]]): Drain[T, Future[M[T]]] =
    Pipe[T].limit(limit).groupedTo[M](Integer.MAX_VALUE, emitSingleEmpty = true).to(head) named "Drain.seq"

  def head[T]: Drain[T, Future[T]] = {
    val promise = Promise[AnyRef]()
    new Drain(new HeadDrainStage(promise), promise.future.asInstanceOf[Future[T]])
  }

  def headOption[T]: Drain[T, Future[Option[T]]] =
    Pipe[T].first.map(Some(_)).nonEmptyOr(Spout.one(None)).to(head) named "Drain.headOption"

  def ignore: Drain[Any, Future[Unit]] = {
    val promise = Promise[Unit]()
    new Drain(new IgnoreDrainStage(promise), promise.future)
  }

  def last[T]: Drain[T, Future[T]] =
    Pipe[T].last.to(head) named "Drain.last"

  def lastOption[T]: Drain[T, Future[Option[T]]] =
    Pipe[T].last.to(headOption) named "Drain.lastOption"

  def lazyStart[T, R](
    onStart: () ⇒ Drain[T, R],
    subscriptionTimeout: Duration = Duration.Undefined)(implicit tf: ToFuture[R]): Drain[T, Future[tf.Out]] = {
    val promise = Promise[tf.Out]()
    val rawOnStart = onStart.asInstanceOf[() ⇒ Drain[AnyRef, AnyRef]]
    val stage = new LazyStartDrainStage(rawOnStart, subscriptionTimeout,
      { x ⇒ promise.completeWith(tf(x.asInstanceOf[R])); () })
    new Drain(stage, promise.future)
  }

  def parallelForeach[T](parallelism: Int)(f: T ⇒ Unit)(implicit ec: ExecutionContext): Drain[T, Future[Unit]] =
    Pipe[T].mapAsyncUnordered(parallelism)(x ⇒ Future(f(x))).to(Drain.ignore)

  def seq[T](limit: Long): Drain[T, Future[immutable.Seq[T]]] =
    generalSeq[immutable.Seq, T](limit)

  def toPublisher[T](fanoutSupport: Boolean = false): Drain[T, Publisher[T]] = {
    if (fanoutSupport) ???
    val stage = new PublisherDrainStage
    new Drain(stage, stage.publisher.asInstanceOf[Publisher[T]])
  }

  private[swave] def apply[T](outport: Outport): Drain[T, Unit] = new Drain(outport, ())

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
