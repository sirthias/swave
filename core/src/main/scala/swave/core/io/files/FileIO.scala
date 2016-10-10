/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.io.files

import java.io.File
import java.nio.file.{Files, Path, StandardOpenOption}
import com.typesafe.config.Config
import swave.core.io.Bytes
import swave.core.util.SettingsCompanion
import swave.core.macros._

object FileIO extends SpoutFromFiles with DrainToFiles {

  val WriteCreateOptions: Set[StandardOpenOption] = {
    import StandardOpenOption._
    Set(CREATE, TRUNCATE_EXISTING, WRITE)
  }

  final case class Settings(defaultFileReadingChunkSize: Int, defaultFileWritingChunkSize: Int) {
    requireArg(defaultFileReadingChunkSize > 0, "`defaultFileChunkSize` must be > 0")
    requireArg(defaultFileWritingChunkSize >= 0, "`defaultFileWritingChunkSize` must be >= 0")
  }

  object Settings extends SettingsCompanion[Settings]("swave.core.file-io") {
    def fromSubConfig(c: Config): Settings =
      Settings(
        defaultFileReadingChunkSize = c getInt "default-file-reading-chunk-size",
        defaultFileWritingChunkSize = c getInt "default-file-writing-chunk-size")
  }

  def writeFile[T: Bytes](fileName: String, data: T): Unit = writeFile(resolveFileSystemPath(fileName), data)
  def writeFile[T: Bytes](file: File, data: T): Unit       = writeFile(file.toPath, data)
  def writeFile[T: Bytes](path: Path, data: T, options: StandardOpenOption*): Unit = {
    implicit def decorator(value: T): Bytes.Decorator[T] = Bytes.decorator(value)
    Files.write(path, data.toArray, options: _*)
    ()
  }

  def readFile[T: Bytes](fileName: String): T = readFile(resolveFileSystemPath(fileName))
  def readFile[T: Bytes](file: File): T       = readFile(file.toPath)
  def readFile[T: Bytes](path: Path): T       = implicitly[Bytes[T]].apply(Files.readAllBytes(path))

}
