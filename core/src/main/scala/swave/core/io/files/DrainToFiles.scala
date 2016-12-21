/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.io.files

import java.io.File
import java.nio.file.{Path, StandardOpenOption}
import scala.concurrent.{Future, Promise}
import swave.core.io.files.impl.FileDrainStage
import swave.core.io.Bytes
import swave.core._

trait DrainToFiles {

  /**
    * Creates a [[Drain]] instance that sinks the incoming byte chunks into the given file.
    * The `options` parameter defines whether the file is to be created, appended to, truncated, etc.
    * If the `chunkSize` parameter is >= 0 it overrides the `swave.core.file-io.default-file-writing-chunk-size`
    * setting.
    * The drain result is a future that's completed with the number of bytes written to the file or
    * any stream error that might have occurred.
    *
    * Since there is no truly async kernel API for file IO and thus file IO is necessarily blocking
    * the actual writing to the file system happens on the dispatcher configured via
    * `swave.core.dispatcher.definition.blocking-io`, i.e. the `Drain` created by this method is always async.
    */
  def toFileName[T: Bytes](fileName: String,
                           options: Set[StandardOpenOption] = FileIO.WriteCreateOptions,
                           chunkSize: Int = -1): Drain[T, Future[Long]] =
    toPath(FileIO.resolveFileSystemPath(fileName), options)

  /**
    * Creates a [[Drain]] instance that sinks the incoming byte chunks into the given file.
    * The `options` parameter defines whether the file is to be created, appended to, truncated, etc.
    * If the `chunkSize` parameter is >= 0 it overrides the `swave.core.file-io.default-file-writing-chunk-size`
    * setting.
    * The drain result is a future that's completed with the number of bytes written to the file or
    * any stream error that might have occurred.
    *
    * Since there is no truly async kernel API for file IO and thus file IO is necessarily blocking
    * the actual writing to the file system happens on the dispatcher configured via
    * `swave.core.dispatcher.definition.blocking-io`, i.e. the `Drain` created by this method is always async.
    */
  def toFile[T: Bytes](file: File,
                       options: Set[StandardOpenOption] = FileIO.WriteCreateOptions,
                       chunkSize: Int = -1): Drain[T, Future[Long]] =
    toPath(file.toPath, options)

  /**
    * Creates a [[Drain]] instance that sinks the incoming byte chunks into the given file.
    * The `options` parameter defines whether the file is to be created, appended to, truncated, etc.
    * If the `chunkSize` parameter is >= 0 it overrides the `swave.core.file-io.default-file-writing-chunk-size`
    * setting.
    * The drain result is a future that's completed with the number of bytes written to the file or
    * any stream error that might have occurred.
    *
    * Since there is no truly async kernel API for file IO and thus file IO is necessarily blocking
    * the actual writing to the file system happens on the dispatcher configured via
    * `swave.core.dispatcher.definition.blocking-io`, i.e. the `Drain` created by this method is always async.
    */
  def toPath[T: Bytes](path: Path,
                       options: Set[StandardOpenOption] = FileIO.WriteCreateOptions,
                       chunkSize: Int = -1): Drain[T, Future[Long]] = {
    val promise = Promise[Long]()
    val drain   = new Drain(new FileDrainStage(path, options, chunkSize, promise), promise.future)
    Pipe[T].asyncBoundary().to(drain.async("blocking-io"))
  }
}
