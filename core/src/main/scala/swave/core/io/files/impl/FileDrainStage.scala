/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.io.files.impl

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Path, StandardOpenOption}
import scala.util.control.NonFatal
import scala.collection.JavaConverters._
import scala.concurrent.Promise
import com.typesafe.scalalogging.Logger
import swave.core.Stage
import swave.core.impl.Inport
import swave.core.impl.stages.DrainStage
import swave.core.io.Bytes
import swave.core.io.files.FileIO
import swave.core.macros._

// format: OFF
@StageImplementation
private[core] final class FileDrainStage[T](path: Path, options: Set[StandardOpenOption], minChunkSize: Int,
                                            resultPromise: Promise[Long])(implicit bytes: Bytes[T])
  extends DrainStage {

  def kind = Stage.Kind.Drain.ToFile(path, options, minChunkSize, resultPromise)

  private[this] val log = Logger(getClass)
  private implicit def decorator(value: T): Bytes.Decorator[T] = Bytes.decorator(value)

  require(options contains StandardOpenOption.WRITE, "`options` must contain `StandardOpenOption.WRITE`")

  connectInAndSealWith { in ⇒
    region.impl.registerForXStart(this)
    val cSize = if (minChunkSize > 0) minChunkSize else region.env.settings.fileIOSettings.defaultFileWritingChunkSize
    running(in, cSize)
  }

  def running(in: Inport, mcs: Int) = {

    def awaitingXStart() = state(
      xStart = () => {
        in.request(Long.MaxValue)
        try writing(FileChannel.open(path, options.asJava), bytes.empty, null, 0L)
        catch {
          case NonFatal(e) =>
            log.debug("Couldn't open `{}` for writing: {}", path, e.toString)
            stopCancel(in)
        }
      })

    def writing(channel: FileChannel, currentChunk: T, byteBuffer: ByteBuffer, totalBytesWritten: Long): State = state(
      onNext = (elem, _) ⇒ {
        try {
          val chunk = currentChunk ++ elem.asInstanceOf[T]
          val chunkSize = chunk.size
          if (chunkSize >= mcs) {
            val buf = write(channel, chunk, byteBuffer)
            writing(channel, bytes.empty, buf, totalBytesWritten + chunkSize)
          } else writing(channel, chunk, byteBuffer, totalBytesWritten)
        } catch {
          case NonFatal(e) =>
            log.debug("Error writing to `{}`: {}", path, e)
            FileIO.quietClose(channel)
            in.cancel()
            stop(e)
        }
      },

      onComplete = _ ⇒ {
        try {
          val totalBytes =
            if (currentChunk.nonEmpty) {
              write(channel, currentChunk, byteBuffer)
              currentChunk.size + totalBytesWritten
            } else totalBytesWritten
          close(channel)
          resultPromise.success(totalBytes)
          stop()
        } catch {
          case NonFatal(e) =>
            log.debug("Error writing to `{}`: {}", path, e)
            FileIO.quietClose(channel)
            stop(e)
        }
      },

      onError = (e, _) ⇒ {
        close(channel)
        resultPromise.failure(e)
        stop(e)
      })

    awaitingXStart()
  }

  private def write(channel: FileChannel, chunk: T, buffer: ByteBuffer): ByteBuffer = {
    val chunkSize =
      chunk.size match {
        case x if x > Int.MaxValue => sys.error("Cannot decode chunk with more than `Int.MaxValue` bytes")
        case x => x.toInt
      }
    val buf =
      if ((buffer ne null) && buffer.capacity >= chunkSize) buffer
      else ByteBuffer.allocate(chunkSize)
    val copied = chunk.copyToBuffer(buf)
    requireState(copied == chunkSize)
    buf.flip()
    channel.write(buf)
    buf.clear()
    buf
  }

  private def close(channel: FileChannel): Unit =
    try channel.close()
    catch {
      case NonFatal(e) => log.debug("Error closing `{}`: {}", path, e)
    }
}
