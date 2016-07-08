/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.io.files.impl

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import com.typesafe.scalalogging.Logger
import scala.annotation.tailrec
import swave.core.PipeElem
import swave.core.impl.Outport
import swave.core.impl.stages.source.SourceStage
import swave.core.io.Bytes
import swave.core.io.files.quietClose
import swave.core.macros._

// format: OFF
@StageImpl
private[core] final class FileSourceStage[T](path: Path, _chunkSize: Int)(implicit bytes: Bytes[T])
  extends SourceStage with PipeElem.Source.File {

  def pipeElemType: String = "Stream.fromPath"
  def pipeElemParams: List[Any] = path :: _chunkSize :: Nil

  private[this] val log = Logger(getClass)
  private implicit def decorator(value: T): Bytes.Decorator[T] = Bytes.decorator(value)

  connectOutAndSealWith { (ctx, out) ⇒
    ctx.registerForXStart(this)
    val cSize = if (_chunkSize > 0) _chunkSize else ctx.env.settings.fileIOSettings.defaultFileReadingChunkSize
    val buf = ByteBuffer.allocate(cSize)
    running(out, cSize, buf)
  }

  def running(out: Outport, chunkSize: Int, buffer: ByteBuffer) = {

    def awaitingXStart() = state(
      xStart = () => {
        var msg = "Couldn't open `{}` for reading: {}"
        var chan: FileChannel = null
        try {
          chan = FileChannel.open(path, FileSourceStage.Read)
          msg = "Couldn't read first chunk of `{}`: {}"
          val chunk = readChunk(chan)
          if (chunk.isEmpty) {
            msg = "Couldn't close empty `{}`: {}"
            chan.close()
            stopComplete(out)
          } else reading(chan, chunk)
        } catch {
          case e: IOException =>
            log.debug(msg, path, e.toString)
            if (chan ne null) quietClose(chan)
            stopError(e, out)
        }
      })

    /**
     *
     * @param channel   the open FileChannel
     * @param nextChunk the currently buffered chunk, non-empty
     */
    def reading(channel: FileChannel, nextChunk: T): State = state(
      request = (n, _) ⇒ {
        @tailrec def rec(remaining: Int, chunk: T): State =
          if (chunk.nonEmpty) {
            if (remaining > 0) {
              out.onNext(chunk.asInstanceOf[AnyRef])
              rec(remaining - 1, readChunk(channel))
            } else reading(channel, chunk)
          } else stopComplete(out)

        out.onNext(nextChunk.asInstanceOf[AnyRef])
        try rec(n - 1, readChunk(channel))
        catch {
          case e: IOException =>
            log.debug("Error reading from `{}`: {}", path, e)
            quietClose(channel)
            stopError(e, out)
        }
      },

      cancel = _ => {
        try channel.close()
        catch {
          case e: IOException =>
            log.debug("Error closing `{}`: {}", path, e)
            stopError(e, out)
        }
        stop()
      })

    def readChunk(chan: FileChannel): T =
      chan.read(buffer) match {
        case -1 => bytes.empty // EOF
        case n =>
          requireState(n > 0, "FileChannel::read returned " + n)
          buffer.flip()
          val result = bytes(buffer)
          buffer.clear()
          result
      }

    awaitingXStart()
  }
}

private object FileSourceStage {
  private val Read = java.util.Collections.singleton(java.nio.file.StandardOpenOption.READ)
}