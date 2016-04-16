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

sealed abstract class StreamEvent[+T]
object StreamEvent {
  sealed abstract class UpEvent extends StreamEvent[Nothing]
  sealed abstract class DownEvent[+T] extends StreamEvent[T]

  final case class Request(elements: Int) extends UpEvent
  case object Cancel extends UpEvent

  final case class OnNext[T](value: T) extends DownEvent[T]
  case object OnComplete extends DownEvent[Nothing]
  final case class OnError(cause: Throwable) extends DownEvent[Nothing]
}
