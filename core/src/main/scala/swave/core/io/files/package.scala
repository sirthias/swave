/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.io

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.{ FileSystems, Path }
import scala.util.control.NonFatal
import swave.core._

package object files {

  implicit class RichStream(val underlying: Stream.type) extends AnyVal with StreamFromFiles
  implicit class RichDrain(val underlying: Drain.type) extends AnyVal with DrainToFiles

  private[io] def quietClose(channel: FileChannel): Unit =
    try channel.close() catch { case NonFatal(_) ⇒ }

  lazy val userHomePath: Path = FileSystems.getDefault.getPath(System getProperty "user.home")

  def resolveFileSystemPath(pathName: String): Path =
    if (pathName.length >= 2 && pathName.charAt(0) == '~' && pathName.charAt(1) == File.separatorChar) {
      userHomePath.resolve(pathName substring 2)
    } else FileSystems.getDefault.getPath(pathName)
}
