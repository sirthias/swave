/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages

private[stages] abstract class StreamTermination {
  def transitionTo(to: StreamTermination): StreamTermination = this
}

private[stages] object StreamTermination {
  case object None extends StreamTermination {
    override def transitionTo(to: StreamTermination): StreamTermination = to
  }
  case object Completed extends StreamTermination
  final case class Error(e: Throwable) extends StreamTermination
}
