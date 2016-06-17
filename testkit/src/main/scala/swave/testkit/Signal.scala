/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.testkit

sealed abstract class Signal

object Signal {
  final case class Request(n: Long) extends Signal
  case object Cancel extends Signal
  final case class OnNext(value: Any) extends Signal
  case object OnComplete extends Signal
  final case class OnError(e: Throwable) extends Signal
}
