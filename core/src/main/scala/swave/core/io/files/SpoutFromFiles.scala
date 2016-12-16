/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.io.files

import java.io.File
import java.nio.file.Path
import swave.core.io.files.impl.FileSpoutStage
import swave.core.io.Bytes
import swave.core._

trait SpoutFromFiles extends Any {

  /**
    * Creates a `Spout` instance that streams the contents of the given file in chunks of the
    * given size (if `chunkSize` > 0) or the configured `swave.core.file-io.default-file-chunk-size`.
    *
    * Since there is no truly async kernel API for file IO and thus file IO is necessarily blocking
    * the actual reading from the file system happens on the dispatcher configured via
    * `swave.core.dispatcher.definition.blocking-io`, i.e. the `Spout` created by this method is always
    * an async stream.
    */
  def fromFileName[T: Bytes](fileName: String, chunkSize: Int = 0): Spout[T] =
    fromPath(resolveFileSystemPath(fileName))

  /**
    * Creates a `Spout` instance that streams the contents of the given file in chunks of the
    * given size (if `chunkSize` > 0) or the configured `swave.core.file-io.default-file-chunk-size`.
    *
    * Since there is no truly async kernel API for file IO and thus file IO is necessarily blocking
    * the actual reading from the file system happens on the dispatcher configured via
    * `swave.core.dispatcher.definition.blocking-io`, i.e. the `Spout` created by this method is always
    * an async stream.
    */
  def fromFile[T: Bytes](file: File, chunkSize: Int = 0): Spout[T] =
    fromPath(file.toPath)

  /**
    * Creates a `Spout` instance that streams the contents of the given file in chunks of the
    * given size (if `chunkSize` > 0) or the configured `swave.core.file-io.default-file-chunk-size`.
    *
    * Since there is no truly async kernel API for file IO and thus file IO is necessarily blocking
    * the actual reading from the file system happens on the dispatcher configured via
    * `swave.core.dispatcher.definition.blocking-io`, i.e. the `Spout` created by this method is always
    * an async stream.
    */
  def fromPath[T: Bytes](path: Path, chunkSize: Int = 0): Spout[T] =
    new Spout(new FileSpoutStage(path, chunkSize)).asyncBoundary("blocking-io")
}
