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

import scala.util.control.NoStackTrace
import swave.core.impl.stages.inout._

sealed abstract class Overflow {
  private[core] def newStage(size: Int): InOutStage
}

object Overflow {

  /**
   * Signals demand to upstream in a way that never allows for buffer overflow
   * and thus never drops any element.
   */
  case object Backpressure extends Overflow {
    private[core] def newStage(size: Int): InOutStage = new BufferBackpressureStage(size)
  }

  /**
   * Signals unlimited demand to upstream and drops the oldest element from the buffer
   * if the buffer is full and a new element arrives.
   */
  case object DropHead extends Overflow {
    private[core] def newStage(size: Int): InOutStage = ???
  }

  /**
   * Signals unlimited demand to upstream and drops the youngest element from the buffer
   * if the buffer is full and a new element arrives.
   */
  case object DropTail extends Overflow {
    private[core] def newStage(size: Int): InOutStage = ???
  }

  /**
   * Signals unlimited demand to upstream and drops all elements from the buffer
   * if the buffer is full and a new element arrives.
   */
  case object DropBuffer extends Overflow {
    private[core] def newStage(size: Int): InOutStage = ???
  }

  /**
   * Signals unlimited demand to upstream and drops the incoming element
   * if the buffer is full and a new element arrives.
   */
  case object DropNew extends Overflow {
    private[core] def newStage(size: Int): InOutStage = ???
  }

  /**
   * Signals unlimited demand to upstream and completes the stream with an `OverflowFailure`
   * if the buffer is full and a new element arrives.
   */
  case object Fail extends Overflow {
    private[core] def newStage(size: Int): InOutStage = ???
  }

  case object OverflowFailure extends RuntimeException with NoStackTrace
}

