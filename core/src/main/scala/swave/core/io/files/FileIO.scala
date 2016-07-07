/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.io.files

import java.io.File
import java.nio.file.Path
import com.typesafe.config.Config
import swave.core.impl.stages.source.FileSourceStage
import swave.core.io.Byteable
import swave.core.macros._
import swave.core.util.SettingsCompanion
import swave.core._

trait FileIO {

  /**
   * Creates a `Stream` instance that streams the contents of the given file in chunks of the
   * given size (if `chunkSize` > 0) or the configured `swave.core.file-io.default-file-chunk-size`.
   *
   * Since there is no truly async kernel API for file IO and thus file IO is necessarily blocking
   * the actual reading from the file system happens on the dispatcher configured via
   * `swave.core.dispatcher.definition.blocking-io`, i.e. the `Stream` created by this method is always
   * an async stream.
   */
  def fromFile[T <: AnyRef](file: File, chunkSize: Int = 0)(implicit b: Byteable[T]): Stream[T] =
    fromPath(file.toPath)

  /**
   * Creates a `Stream` instance that streams the contents of the given file in chunks of the
   * given size (if `chunkSize` > 0) or the configured `swave.core.file-io.default-file-chunk-size`.
   *
   * Since there is no truly async kernel API for file IO and thus file IO is necessarily blocking
   * the actual reading from the file system happens on the dispatcher configured via
   * `swave.core.dispatcher.definition.blocking-io`, i.e. the `Stream` created by this method is always
   * an async stream.
   */
  def fromPath[T <: AnyRef](path: Path, chunkSize: Int = 0)(implicit b: Byteable[T]): Stream[T] =
    new Stream(new FileSourceStage(path, chunkSize)).async("blocking-io")
}

object FileIO extends FileIO {

  final case class Settings(defaultFileChunkSize: Int) {
    requireArg(defaultFileChunkSize > 0, "`defaultFileChunkSize` must be > 0")
  }

  object Settings extends SettingsCompanion[Settings]("swave.core.file-io") {
    def fromSubConfig(c: Config): Settings =
      Settings(
        defaultFileChunkSize = c getInt "default-file-chunk-size")
  }

}
