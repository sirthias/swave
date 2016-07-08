/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.io.files

import java.nio.file.StandardOpenOption
import com.typesafe.config.Config
import swave.core.util.SettingsCompanion
import swave.core.macros._

object FileIO extends StreamFromFiles with DrainToFiles {

  val WriteCreateOptions: Set[StandardOpenOption] = Set(StandardOpenOption.WRITE, StandardOpenOption.CREATE)

  final case class Settings(
      defaultFileReadingChunkSize: Int,
      defaultFileWritingChunkSize: Int) {
    requireArg(defaultFileReadingChunkSize > 0, "`defaultFileChunkSize` must be > 0")
    requireArg(defaultFileWritingChunkSize >= 0, "`defaultFileWritingChunkSize` must be >= 0")
  }

  object Settings extends SettingsCompanion[Settings]("swave.core.file-io") {
    def fromSubConfig(c: Config): Settings =
      Settings(
        defaultFileReadingChunkSize = c getInt "default-file-reading-chunk-size",
        defaultFileWritingChunkSize = c getInt "default-file-writing-chunk-size")
  }

}
