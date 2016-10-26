/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import scala.util.control.NoStackTrace

object Buffer {

  /**
    * The `OverflowStrategy` defines, how a `bufferDropping` operation should behave, when
    * all available buffer space has been filled.
    */
  sealed abstract class OverflowStrategy(private[core] val id: Int)

  object OverflowStrategy {

    /**
      * Signals unlimited demand to upstream and drops the oldest element from the buffer
      * if the buffer is full and a new element arrives.
      */
    case object DropHead extends OverflowStrategy(1)

    /**
      * Signals unlimited demand to upstream and drops the youngest element from the buffer
      * if the buffer is full and a new element arrives.
      */
    case object DropTail extends OverflowStrategy(2)

    /**
      * Signals unlimited demand to upstream and drops all elements from the buffer
      * if the buffer is full and a new element arrives.
      */
    case object DropBuffer extends OverflowStrategy(3)

    /**
      * Signals unlimited demand to upstream and drops the incoming element
      * if the buffer is full and a new element arrives.
      */
    case object DropNew extends OverflowStrategy(4)

    /**
      * Signals unlimited demand to upstream and completes the stream with an `OverflowFailure`
      * if the buffer is full and a new element arrives.
      */
    case object Fail extends OverflowStrategy(5)

    /**
      * The exception instance thrown by the [[OverflowFailure]] [[OverflowStrategy]]
      * when the buffer is full and a new element arrives.
      */
    case object OverflowFailure extends RuntimeException with NoStackTrace
  }

  /**
    * The `RequestStrategy` defines, when a `buffer` operation should request new elements from its
    * upstream. Given a buffer size it returns a threshold value. The next request is only
    * triggered when the free capacity of the buffer if larger than this threshold.
    *
    * Note: All the dropping [[OverflowStrategy]] variants always signal unlimited demand to their
    * upstreams and therefore don't need a `RequestStrategy`.
    */
  type RequestStrategy = Int => Int

  object RequestStrategy {

    /**
      * Always requests immediately, as soon as buffer space becomes available.
      * This strategy tries to keep the buffer always as full as possible at the expense of triggering
      * more `request` signals and therefore potentially increasing overhead by reducing demand batching.
      */
    val Always: RequestStrategy = _ => 0

    /**
      * Triggers a request to upstream when the buffer becomes less than half full.
      * Offers a good compromise between [[Always]] and [[WhenEmpty]].
      */
    val WhenHalfEmpty: RequestStrategy = _ >> 1

    /**
      * Only triggers the next request to upstream when the buffer has run completely empty.
      * While this results in the greatest degree of demand batching it also might increase latency
      * for some elements.
      */
    val WhenEmpty: RequestStrategy = _ - 1
  }
}