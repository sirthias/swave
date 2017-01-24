/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.text

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset._
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import swave.core.{Pipe, Spout}
import swave.core.io.Bytes
import swave.core.macros._
import swave.core.util._

object Text extends TextTransformations

trait TextTransformations {

  final def utf8Decode[T: Bytes]: Pipe[T, String] = decode(UTF8)

  final def decode[T: Bytes](charset: Charset,
                             onMalformedInput: CodingErrorAction = CodingErrorAction.REPORT,
                             onUnmappableCharacter: CodingErrorAction = CodingErrorAction.REPLACE): Pipe[T, String] =
    Pipe[T].map {
      new (T => String) {
        implicit def decorator(value: T): Bytes.Decorator[T] = Bytes.decorator(value)

        private[this] val decoder                = charset.newDecoder
        private[this] var byteBuffer: ByteBuffer = _
        private[this] var charBuffer: CharBuffer = _

        decoder.onMalformedInput(onMalformedInput)
        decoder.onUnmappableCharacter(onUnmappableCharacter)

        def apply(bytes: T): String = {
          val size =
            bytes.size match {
              case x if x > Int.MaxValue => sys.error("Cannot decode chunk with more than `Int.MaxValue` bytes")
              case x                     => x.toInt
            }
          if ((byteBuffer eq null) || byteBuffer.remaining < size) {
            byteBuffer = if ((byteBuffer ne null) && byteBuffer.position > 0) {
              val b = ByteBuffer.allocate(byteBuffer.position + size)
              byteBuffer.flip()
              b.put(byteBuffer)
              b
            } else ByteBuffer.allocate(size)
            charBuffer = CharBuffer.allocate((size * decoder.averageCharsPerByte).toInt)
          }
          val copied = bytes.copyToBuffer(byteBuffer)
          requireState(copied == size)
          byteBuffer.flip()
          decode("")
        }

        private def decode(prefix: String): String = {
          def string() = {
            charBuffer.flip()
            val result = if (prefix.isEmpty) charBuffer.toString else prefix + charBuffer.toString
            charBuffer.clear()
            result
          }
          decoder.decode(byteBuffer, charBuffer, false) match {
            case CoderResult.UNDERFLOW =>
              byteBuffer.compact()
              string()
            case CoderResult.OVERFLOW => decode(string())
            case x                    => x.throwException().asInstanceOf[Nothing]
          }
        }
      }
    } named "decode"

  final def utf8Encode[T: Bytes]: Pipe[String, T] = encode(UTF8)

  final def encode[T](charset: Charset)(implicit ev: Bytes[T]): Pipe[String, T] =
    Pipe[String] map {
      new (String => T) {
        implicit def decorator(value: T): Bytes.Decorator[T] = Bytes.decorator(value)

        private[this] val encoder                = charset.newEncoder()
        private[this] var charBuffer: CharBuffer = _
        private[this] var byteBuffer: ByteBuffer = _

        def apply(string: String): T = {
          val size = string.length
          if ((charBuffer eq null) || charBuffer.capacity < size) {
            charBuffer = CharBuffer.allocate(size)
            byteBuffer = ByteBuffer.allocate((size * encoder.averageBytesPerChar()).toInt)
          }
          charBuffer.put(string)
          charBuffer.flip()
          encode(ev.empty)
        }

        private def encode(prefix: T): T = {
          def bytes = {
            byteBuffer.flip()
            val result = if (prefix.isEmpty) ev(byteBuffer) else prefix ++ ev(byteBuffer)
            byteBuffer.clear()
            result
          }
          encoder.encode(charBuffer, byteBuffer, false) match {
            case CoderResult.UNDERFLOW =>
              requireState(charBuffer.remaining == 0)
              charBuffer.clear()
              bytes
            case CoderResult.OVERFLOW => encode(bytes)
            case x                    => x.throwException().asInstanceOf[Nothing]
          }
        }
      }
    } named "encode"

  private val EOI = new String

  final def lines: Pipe[String, String] = // TODO: upgrade to more efficient custom stage once it's available
    Pipe[String].concat(Spout.one(EOI)) flatMap {
      new (String => List[String]) {
        private[this] val sb = new java.lang.StringBuilder
        def apply(input: String): List[String] =
          if (input ne EOI) {
            @tailrec def rec(ix: Int, buf: ListBuffer[String]): List[String] =
              if (ix < input.length) {
                input(ix) match {
                  case '\n' =>
                    val b = if (buf eq null) new ListBuffer[String] else buf
                    b += sb.toString
                    sb.setLength(0)
                    rec(ix + 1, b)
                  case c =>
                    sb.append(c)
                    rec(ix + 1, buf)
                }
              } else if (buf eq null) Nil
              else buf.toList
            rec(0, null)
          } else if (sb.length > 0) sb.toString :: Nil
          else Nil
      }
    } named "lines"
}
