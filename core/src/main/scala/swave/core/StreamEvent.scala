/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

sealed abstract class StreamEvent[+T]
object StreamEvent {
  sealed abstract class UpEvent       extends StreamEvent[Nothing]
  sealed abstract class DownEvent[+T] extends StreamEvent[T]

  final case class Request(elements: Int) extends UpEvent
  case object Cancel                      extends UpEvent

  final case class OnNext[T](value: T)       extends DownEvent[T]
  case object OnComplete                     extends DownEvent[Nothing]
  final case class OnError(cause: Throwable) extends DownEvent[Nothing]
}
