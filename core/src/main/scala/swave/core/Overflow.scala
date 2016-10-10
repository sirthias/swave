/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.util.control.NoStackTrace
import swave.core.impl.stages.inout._

sealed abstract class Overflow(val id: Int) {
  private[core] def newStage(size: Int): InOutStage = new BufferDroppingStage(size, this)
}

object Overflow {

  /**
    * Signals demand to upstream in a way that never allows for buffer overflow
    * and thus never drops any element.
    */
  case object Backpressure extends Overflow(0) {
    private[core] override def newStage(size: Int): InOutStage = new BufferBackpressureStage(size)
  }

  /**
    * Signals unlimited demand to upstream and drops the oldest element from the buffer
    * if the buffer is full and a new element arrives.
    */
  case object DropHead extends Overflow(1)

  /**
    * Signals unlimited demand to upstream and drops the youngest element from the buffer
    * if the buffer is full and a new element arrives.
    */
  case object DropTail extends Overflow(2)

  /**
    * Signals unlimited demand to upstream and drops all elements from the buffer
    * if the buffer is full and a new element arrives.
    */
  case object DropBuffer extends Overflow(3)

  /**
    * Signals unlimited demand to upstream and drops the incoming element
    * if the buffer is full and a new element arrives.
    */
  case object DropNew extends Overflow(4)

  /**
    * Signals unlimited demand to upstream and completes the stream with an `OverflowFailure`
    * if the buffer is full and a new element arrives.
    */
  case object Fail extends Overflow(5)

  case object OverflowFailure extends RuntimeException with NoStackTrace
}
