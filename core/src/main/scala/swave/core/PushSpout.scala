/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import org.jctools.queues.MpscChunkedArrayQueue

import scala.collection.immutable
import swave.core.impl.stages.spout.PushSpoutStage
import swave.core.util._

import scala.annotation.tailrec

/**
  * A [[PushSpout]] provides a [[Spout]] that can be "manually" pushed into from the outside,
  * potentially by several threads concurrently.
  *
  * It manages an internal queue which allows for compensation of some fluctuation in demand from downstream
  * (i.e. back-pressure). If the queue is full all further pushes are rejected, i.e. no elements are being dropped.
  *
  * @param initialBufferSize the initial buffer size, must be >= 2.
  * @param maxBufferSize the max size the buffer is allowed to grow to if required, must be >= 4, will be rounded up to
  *                      the closest power of 2 and round up to a larger power of 2 than `initialBufferSize`.
  * @param growByInitialSize if the true the buffer size is incremented in steps of `initialBufferSize`, otherwise
  *                          it grows exponentially by doubling in size at each resize step
  * @param notifyOnDequeued callback that will be called each time a `request` signal from downstream has been handled,
  *                         i.e. whenever one or more elements have been dequeued and pushed to downstream.
  *                         The argument to the handler is the number of elements dequeued and is always > 0.
  *                         NOTE: Might be called from another thread if the stream is asynchronous!
  *                         Handler should be light-weight and never block!
  * @param notifyOnCancel callback that will be called when the downstream has actively cancelled the stream.
  *                       This might be even after manual completion via the `complete` method!
  *                       NOTE: Might be called from another thread if the stream is asynchronous!
  *                       Handler should be light-weight and never block!
  */
final class PushSpout[+A] private (val initialBufferSize: Int,
                                   val maxBufferSize: Int,
                                   val growByInitialSize: Boolean,
                                   notifyOnDequeued: (PushSpout[A], Int) ⇒ Unit,
                                   notifyOnCancel: PushSpout[A] ⇒ Unit) {

  private[this] val queue = new MpscChunkedArrayQueue[AnyRef](initialBufferSize, maxBufferSize, growByInitialSize)
  private[this] val stage = new PushSpoutStage(queue, notifyOnDequeued(this, _), () ⇒ notifyOnCancel(this))

  /**
    * The actual spout instance.
    *
    * NOTE: The [[PushSpout]] companion defines an implicit conversion to this instance so in many cases
    * you should be able omit the explicit selection of this member.
    */
  val spout: Spout[A] = new Spout(stage)

  /**
    * Returns true if the queue still has buffer space available.
    *
    * NOTE: If more than one thread push elements in an unsynchronized fashion
    * the result of this method is essentially meaningless.
    */
  def queueSize: Int = queue.size()

  /**
    * Returns true if the queue still has buffer space available.
    *
    * NOTE: If more than one thread push elements in an unsynchronized fashion
    * the result of this method is essentially meaningless.
    */
  def acceptsNext: Boolean = queueSize < maxBufferSize

  /**
    * Tries to push the given value into the stream, which will succeed if back-pressure
    * from downstream hasn't yet caused the complete buffer space to be filled up.
    *
    * @return true if the element was successfully scheduled, false if the buffer is full and further growth impossible
    */
  def offer[B >: A](element: B): Boolean = {
    val wasAdded = queue.offer(element.asInstanceOf[AnyRef])
    if (wasAdded) stage.enqueueXEvent(PushSpoutStage.Signal.NewAvailable)
    wasAdded
  }

  /**
    * Tries to push the given values into the stream.
    * Depending on the available buffer space this might succeed completely, partially or not at all.
    *
    * @return the number of elements that were successfully pushed
    */
  def offerMany[B >: A](elements: immutable.Iterable[B]): Int = {
    val iter = elements.iterator
    @tailrec def rec(count: Int): Int =
      if (iter.hasNext && queue.offer(iter.next().asInstanceOf[AnyRef])) {
        rec(count + 1)
      } else {
        if (count > 0) stage.enqueueXEvent(PushSpoutStage.Signal.NewAvailable)
        count
      }
    rec(0)
  }

  /**
    * Completes the stream with an `onComplete` signal.
    */
  def complete(): Unit = stage.enqueueXEvent(PushSpoutStage.Signal.Complete)

  /**
    * Completes the stream with an `onError` signal.
    */
  def errorComplete(e: Throwable): Unit = stage.enqueueXEvent(PushSpoutStage.Signal.ErrorComplete(e))
}

object PushSpout {

  /**
    * Creates a new [[PushSpout]].
    */
  def apply[T](initialBufferSize: Int,
               maxBufferSize: Int,
               growByInitialSize: Boolean = false,
               notifyOnDequeued: (PushSpout[T], Int) ⇒ Unit = dropFunc2,
               notifyOnCancel: PushSpout[T] ⇒ Unit = dropFunc): PushSpout[T] =
    new PushSpout(initialBufferSize, maxBufferSize, growByInitialSize, notifyOnDequeued, notifyOnCancel)

  /**
    * Allows a [[PushSpout] to be used (almost) everywhere where a [[Spout]] is expected.
    */
  implicit def pushSpoutToSpout[T](ps: PushSpout[T]): Spout[T] = ps.spout
}
