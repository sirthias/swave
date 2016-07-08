/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.io.files

import java.io.File
import java.nio.file.Path
import swave.core.io.files.impl.FileSourceStage
import swave.core.io.Bytes
import swave.core._

trait StreamFromFiles extends Any {

  /**
   * Creates a `Stream` instance that streams the contents of the given file in chunks of the
   * given size (if `chunkSize` > 0) or the configured `swave.core.file-io.default-file-chunk-size`.
   *
   * Since there is no truly async kernel API for file IO and thus file IO is necessarily blocking
   * the actual reading from the file system happens on the dispatcher configured via
   * `swave.core.dispatcher.definition.blocking-io`, i.e. the `Stream` created by this method is always
   * an async stream.
   */
  def fromFile[T: Bytes](file: File, chunkSize: Int = 0): Stream[T] =
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
  def fromPath[T: Bytes](path: Path, chunkSize: Int = 0): Stream[T] =
    new Stream(new FileSourceStage(path, chunkSize)).async("blocking-io")
}

