/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

sealed abstract class Split

object Split {
  sealed abstract class Command

  /**
    * Emit the current element to the current sub-stream.
    * If there is no sub-stream open, start a new one by emitting a new sub-stream to the main downstream.
    */
  case object Emit extends Command

  /**
    * Drop the current element without any effect on a potentially open sub-stream or the main downstream.
    */
  case object Drop extends Command

  /**
    * Emit the current element to the current sub-stream and complete the sub-stream afterwards.
    * If there is no sub-stream open, emit a single-element sub-stream on the main downstream.
    */
  case object EmitComplete extends Command

  /**
    * If there is currently a sub-stream open, complete it.
    * Then emit a new sub-stream to the main downstream and emit the current element this this new sub-stream.
    */
  case object CompleteEmit extends Command

  /**
    * Drop the current element and complete the currently open sub-stream, if there is one.
    */
  case object DropComplete extends Command
}
