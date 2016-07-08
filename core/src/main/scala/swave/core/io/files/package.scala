/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.io

import java.nio.channels.FileChannel

import swave.core._

import scala.util.control.NonFatal

package object files {

  implicit class RichStream(val underlying: Stream.type) extends AnyVal with StreamFromFiles
  implicit class RichDrain(val underlying: Drain.type) extends AnyVal with DrainToFiles

  private[io] def quietClose(channel: FileChannel): Unit =
    try channel.close() catch { case NonFatal(_) ⇒ }
}
