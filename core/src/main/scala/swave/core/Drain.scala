/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import scala.collection.generic.CanBuildFrom
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import org.reactivestreams.{Publisher, Subscriber}
import swave.core.impl.TypeLogic.ToFuture
import swave.core.impl.Outport
import swave.core.impl.stages.DrainStage
import swave.core.impl.stages.drain._

/**
  * A `Drain` is a streaming component] with one input port and a no output port. As such it serves
  * as a "point of exit" from a stream graph to other destinations (e.g. to memory, disk or the network).
  *
  * @tparam T the type of the data elements consumed
  * @tparam R the type of "results" that the drain provides
  */
final class Drain[-T, +R] private[swave] (private[swave] val outport: Outport, val result: R) {

  /**
    * The [[Stage]] representation of this [[Drain]].
    */
  def stage: Stage = outport.stageImpl

  /**
    * Turns this [[Drain]] into one that produces no result.
    * Rather the result of this drain fulfills the given [[Promise]] as soon as it becomes available.
    * If the result of this drain is a [[Future]], the [[Future]] layer is unwrapped.
    *
    * NOTE: The result of this call and the underlying drain share the same stage.
    * This means that only one of them can be used (once) in any stream setup.
    */
  def captureResult[P](promise: Promise[P])(implicit capture: Drain.Capture[R, P]): Drain[T, Unit] = {
    capture(result, promise)
    dropResult
  }

  /**
    * Turns this [[Drain]] into one that produces no result by simply dropping the result value.
    *
    * NOTE: The result of this call and the underlying drain share the same stage.
    * This means that only one of them can be used (once) in any stream setup.
    */
  def dropResult: Drain[T, Unit] = Drain(outport)

  /**
    * Turns this [[Drain]] into one with a different result by mapping over the result value.
    *
    * NOTE: The result of this call and the underlying drain share the same stage.
    * This means that only one of them can be used (once) in any stream setup.
    */
  def mapResult[P](f: R ⇒ P): Drain[T, P] = new Drain(outport, f(result))

  /**
    * Explicitly attaches the given name to this [[Drain]].
    */
  def named(name: String): this.type = {
    Module.ID(name).addBoundary(Module.Boundary.InnerEntry(outport.stageImpl))
    this
  }

  /**
    * Turns this [[Drain]] into one that causes the stream graph it's incorporated into to run asynchronously.
    * If the given `dispatcherId` is empty the default dispatcher will be assigned if no other non-default
    * assignment has been made for the async region the drain is placed in.
    *
    * Since this [[Drain]] might internally consist of a whole other stream graph the full description of
    * this method's effect is: Marks all primary drains in the graph behind this drain's stage as
    * "to be run on the dispatcher with the given id" or the default-dispatcher.
    *
    * NOTE: The (internal) graph behind this drain's stage must not contain any explicit async boundaries.
    * Otherwise an [[IllegalStateException]] will be thrown.
    */
  def async(dispatcherId: String = ""): Drain[T, R] = {
    outport match {
      case x: DrainStage ⇒ x.assignDispatcherId(dispatcherId)
      case x: Stage      ⇒ DrainStage.assignToAllPrimaryDrains(x, dispatcherId)
      case _             ⇒ throw new IllegalStateException
    }
    this
  }

  private[core] def consume(spout: Spout[T]): R = {
    spout.inport.subscribe()(outport)
    result
  }
}

object Drain {

  /**
    * A [[Drain]] which signals no demand but immediately cancels its upstream and produces no result.
    */
  def cancelling: Drain[Any, Unit] =
    Drain(new CancellingDrainStage)

  /**
    * A [[Drain]] which demands `n` elements, collects them and produces an [[immutable.Seq]].
    */
  def first[T](n: Int): Drain[T, Future[immutable.Seq[T]]] =
    Pipe[T].grouped(n, emitSingleEmpty = true).to(head) named "Drain.first"

  /**
    * A [[Drain]] which signals infinite demand and executes the given callback for each received element.
    *
    * CAUTION: `callback` will be called from another thread if the stream is asynchronous!
    */
  def foreach[T](callback: T ⇒ Unit): Drain[T, Future[Unit]] = {
    val promise = Promise[Unit]()
    new Drain(new ForeachDrainStage(callback.asInstanceOf[AnyRef ⇒ Unit], promise), promise.future)
  }

  /**
    * A [[Drain]] which signals infinite demand and executes the given folding function over the received elements.
    *
    * CAUTION: `f` will be called from another thread if the stream is asynchronous!
    */
  def fold[T, R](zero: R)(f: (R, T) ⇒ R): Drain[T, Future[R]] =
    Pipe[T].fold(zero)(f).to(head) named "Drain.fold"

  /**
    * A [[Drain]] which drains the stream into the given [[Subscriber]].
    * Both are directly linked to each other and thereby allow for connecting *swave* with other
    * Reactive Streams implementations.
    *
    * The returned [[Drain]] instance cannot run synchronously.
    */
  def fromSubscriber[T](subscriber: Subscriber[T]): Drain[T, Unit] =
    Drain(new SubscriberDrainStage(subscriber.asInstanceOf[Subscriber[AnyRef]]))

  /**
    * A [[Drain]] which signals demand of `Integer.MAX_VALUE` and buffers incoming elements in a collection
    * of type `M[+_]`. The given `limit` protects against an overflow beyond the expected maximum number
    * of elements by failing the stream with a [[StreamLimitExceeded]] if more elements are received.
    */
  def generalSeq[M[+ _], T](limit: Int)(implicit cbf: CanBuildFrom[M[T], T, M[T]]): Drain[T, Future[M[T]]] =
    Pipe[T].withLimit(limit.toLong).groupedTo[M](Integer.MAX_VALUE, emitSingleEmpty = true).to(head) named "Drain.seq"

  /**
    * A [[Drain]] which only requests and produces the very first stream element.
    * If the stream completes without having produced a single element the result future is completed with
    * a [[NoSuchElementException]].
    */
  def head[T]: Drain[T, Future[T]] = {
    val promise = Promise[AnyRef]()
    new Drain(new HeadDrainStage(promise), promise.future.asInstanceOf[Future[T]])
  }

  /**
    * A [[Drain]] which only requests and produces the very first stream element as an [[Option]].
    */
  def headOption[T]: Drain[T, Future[Option[T]]] =
    Pipe[T].first.map(Some(_)).orElse(Spout.one(None)).to(head) named "Drain.headOption"

  /**
    * A [[Drain]] which signals infinite demand and simply drops all received elements.
    * The produced [[Future]] is completed with the stream's termination.
    */
  def ignore: Drain[Any, Future[Unit]] = {
    val promise = Promise[Unit]()
    new Drain(new IgnoreDrainStage(promise), promise.future)
  }

  /**
    * A [[Drain]] which signals infinite demand and drops all but the last of the received elements.
    * If the stream completes without having produced a single element the result future is completed with
    * a [[NoSuchElementException]].
    */
  def last[T]: Drain[T, Future[T]] =
    Pipe[T].last.to(head) named "Drain.last"

  /**
    * A [[Drain]] which signals infinite demand and drops all but the last of the received elements,
    * which it produces as an [[Option]].
    */
  def lastOption[T]: Drain[T, Future[Option[T]]] =
    Pipe[T].last.to(headOption) named "Drain.lastOption"

  /**
    * A [[Drain]] which forward the stream to the [[Drain]] instance returned by the given
    * function. The given function is only executed when the outer stream is started.
    *
    * If the outer stream is synchronous the [[Drain]] returned by `onStart` must be able to run synchronously
    * as well. If it doesn't the stream will fail with an [[IllegalAsyncBoundaryException]].
    */
  def lazyStart[T, R](onStart: () ⇒ Drain[T, R])(implicit tf: ToFuture[R]): Drain[T, Future[tf.Out]] = {
    val promise    = Promise[tf.Out]()
    val rawOnStart = onStart.asInstanceOf[() ⇒ Drain[AnyRef, AnyRef]]
    val stage = new LazyStartDrainStage(rawOnStart, { x ⇒
      promise.completeWith(tf(x.asInstanceOf[R])); ()
    })
    new Drain(stage, promise.future)
  }

  /**
    * A [[Drain]] which produces a single string representation of the stream by concatenating
    * the `toString` result of all elements, optionally separated by `sep`.
    *
    * The given `limit` protects against an overflow beyond the expected maximum number
    * of elements by failing the stream with a [[StreamLimitExceeded]] if more elements are received.
    */
  def mkString[T](limit: Int, sep: String = ""): Drain[T, Future[String]] =
    mkString(limit, "", sep, "")

  /**
    * A [[Drain]] which produces a single string representation of the stream by concatenating
    * the `toString` result of all elements with the given `start`, `sep` and `end` strings.
    *
    * The given `limit` protects against an overflow beyond the expected maximum number
    * of elements by failing the stream with a [[StreamLimitExceeded]] if more elements are received.
    */
  def mkString[T](limit: Int, start: String, sep: String, end: String): Drain[T, Future[String]] = {
    var first = true
    val pipe = Pipe[T]
      .fold(new java.lang.StringBuilder(start)) { (sb, elem) =>
        if (first) first = false else sb.append(sep)
        sb.append(elem)
      }
      .map(_.append(end).toString)
    pipe to Drain.head
  }

  /**
    * A [[Drain]] which signals infinite demand and executes the given callback for each received element.
    * Depending on the arrival rate of the stream elements as well as the runtime of the callback up to
    * `parallelism` invocations of the callback function will be run in parallel, i.e. in an overlapping fashion.
    *
    * CAUTION: `callback` will be called from another thread if the stream is asynchronous!
    */
  def parallelForeach[T](parallelism: Int)(f: T ⇒ Unit)(implicit ec: ExecutionContext): Drain[T, Future[Unit]] =
    Pipe[T].mapAsyncUnordered(parallelism)(x ⇒ Future(f(x))).to(Drain.ignore)

  /**
    * A [[Drain]] which signals demand of `Integer.MAX_VALUE` and buffers incoming elements in an
    * [[immutable.Seq]]. The given `limit` protects against an overflow beyond the expected maximum number
    * of elements by failing the stream with a [[StreamLimitExceeded]] if more elements are received.
    */
  def seq[T](limit: Int): Drain[T, Future[immutable.Seq[T]]] =
    generalSeq[immutable.Seq, T](limit)

  /**
    * A [[Drain]] which produces an associated [[Publisher]].
    * Both are directly linked to each other and thereby allow for connecting *swave* with other
    * Reactive Streams implementations.
    *
    * The [[Publisher]] produces all elements received by the [[Drain]] and
    * the [[Drain]] forwards all request and cancel signals received by the [[Publisher]].
    *
    * The returned [[Drain]] instance cannot run synchronously.
    */
  def toPublisher[T](fanoutSupport: Boolean = false): Drain[T, Publisher[T]] = {
    if (fanoutSupport) ???
    val stage = new PublisherDrainStage
    new Drain(stage, stage.publisher.asInstanceOf[Publisher[T]])
  }

  /////////////////////// INTERNAL ////////////////////////////

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
