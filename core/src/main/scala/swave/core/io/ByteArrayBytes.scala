/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.io

import java.io.OutputStream
import java.nio.charset.{CharacterCodingException, Charset}
import java.nio.{ByteBuffer, CharBuffer}
import java.util
import scala.annotation.tailrec
import scala.collection.GenTraversableOnce
import swave.core.macros._

class ByteArrayBytes extends Bytes[Array[Byte]] {

  ///////////////// CONSTRUCTION ///////////////////

  def empty = ByteArrayBytes.Empty
  def fill[A: Integral](size: Long)(byte: A) = {
    requireArg(0 <= size && size <= Int.MaxValue, "`size` must be >= 0 and <= Int.MaxValue")
    val b     = implicitly[Integral[A]].toInt(byte).toByte
    val array = new Array[Byte](size.toInt)
    util.Arrays.fill(array, b)
    array
  }
  def apply(array: Array[Byte]) = array
  def apply(bytes: Array[Byte], offset: Int, length: Int) =
    util.Arrays.copyOfRange(bytes, offset, offset + length)
  def apply[A: Integral](bytes: A*) = {
    val integral = implicitly[Integral[A]]
    val buf      = new Array[Byte](bytes.size)
    @tailrec def rec(ix: Int): Array[Byte] =
      if (ix < buf.length) {
        buf(ix) = integral.toInt(bytes(ix)).toByte
        rec(ix + 1)
      } else buf
    rec(0)
  }
  def apply(bytes: Vector[Byte]) = bytes.toArray
  def apply(buffer: ByteBuffer) = {
    val array = new Array[Byte](buffer.remaining)
    buffer.get(array)
    array
  }
  def apply(bs: GenTraversableOnce[Byte])         = bs.toArray
  def view(bytes: Array[Byte])                    = apply(bytes) // no view-like constructor available for byte arrays
  def view(bytes: ByteBuffer)                     = apply(bytes) // no view-like constructor available for byte arrays
  def encodeString(str: String, charset: Charset) = str getBytes charset
  def encodeStringStrict(str: String, charset: Charset) =
    try Right(apply(charset.newEncoder.encode(CharBuffer.wrap(str))))
    catch { case e: CharacterCodingException ⇒ Left(e) }

  ///////////////// QUERY ///////////////////

  def size(value: Array[Byte]): Long = value.length.toLong
  def byteAt(value: Array[Byte], ix: Long): Byte = {
    requireArg(0 <= ix && ix <= Int.MaxValue, "`ix` must be >= 0 and <= Int.MaxValue")
    value(ix.toInt)
  }
  def indexOfSlice(value: Array[Byte], slice: Array[Byte], startIx: Long): Long = {
    requireArg(0 <= startIx && startIx <= Int.MaxValue, "`startIx` must be >= 0 and <= Int.MaxValue")
    value.indexOfSlice(slice, startIx.toInt).toLong
  }

  ///////////////// TRANSFORMATION TO Array[Byte] ///////////////////

  def update(value: Array[Byte], ix: Long, byte: Byte) = {
    requireArg(0 <= ix && ix <= Int.MaxValue, "`ix` must be >= 0 and <= Int.MaxValue")
    val array = util.Arrays.copyOf(value, value.length)
    array(ix.toInt) = byte
    array
  }
  def concat(value: Array[Byte], other: Array[Byte]) =
    if (value.length > 0) {
      if (other.length > 0) {
        val len = value.length.toLong + other.length
        requireArg(0 <= len && len <= Int.MaxValue, "concatenated length must be >= 0 and <= Int.MaxValue")
        val array = util.Arrays.copyOf(value, len.toInt)
        System.arraycopy(other, 0, array, value.length, other.length)
        array
      } else value
    } else other
  def concat(value: Array[Byte], byte: Byte) = {
    val len = value.length
    if (value.length > 0) {
      requireArg(0 <= len && len <= Int.MaxValue - 1, "concatenated length must be >= 0 and <= Int.MaxValue - 1")
      val array = new Array[Byte](len + 1)
      System.arraycopy(value, 0, array, 0, len)
      array(len) = byte
      array
    } else singleByteArray(byte)
  }
  def concat(byte: Byte, value: Array[Byte]) = {
    val len = value.length
    if (value.length > 0) {
      requireArg(0 <= len && len <= Int.MaxValue - 1, "concatenated length must be >= 0 and <= Int.MaxValue - 1")
      val array = new Array[Byte](len + 1)
      System.arraycopy(value, 0, array, 1, len)
      array(0) = byte
      array
    } else singleByteArray(byte)
  }
  private def singleByteArray(byte: Byte) = {
    val array = new Array[Byte](1)
    array(0) = byte
    array
  }
  def drop(value: Array[Byte], n: Long) = {
    requireArg(0 <= n && n <= Int.MaxValue, "`n` must be >= 0 and <= Int.MaxValue")
    if (n > 0) if (n < value.length) util.Arrays.copyOfRange(value, n.toInt, value.length) else empty else value
  }
  def take(value: Array[Byte], n: Long) = {
    requireArg(0 <= n && n <= Int.MaxValue, "`n` must be >= 0 and <= Int.MaxValue")
    if (n > 0) if (n < value.length) util.Arrays.copyOfRange(value, 0, n.toInt) else value else empty
  }
  def map(value: Array[Byte], f: Byte ⇒ Byte) = value.map(f)
  def reverse(value: Array[Byte])             = value.reverse
  def compact(value: Array[Byte])             = value

  ///////////////// TRANSFORMATION TO OTHER TYPES ///////////////////

  def toArray(value: Array[Byte]) = value
  def copyToArray(value: Array[Byte], xs: Array[Byte], offset: Int) =
    System.arraycopy(value, 0, xs, offset, math.max(0, math.min(value.length, xs.length - offset)))
  def copyToArray(value: Array[Byte], sourceOffset: Long, xs: Array[Byte], destOffset: Int, len: Int) = {
    requireArg(0 <= sourceOffset && sourceOffset <= Int.MaxValue, "`sourceOffset` must be >= 0 and <= Int.MaxValue")
    System.arraycopy(value, sourceOffset.toInt, xs, destOffset, len)
  }
  def copyToBuffer(value: Array[Byte], buffer: ByteBuffer): Int = {
    val len = math.min(value.length, buffer.remaining)
    buffer.put(value)
    len
  }
  def copyToOutputStream(value: Array[Byte], s: OutputStream) = s.write(value)
  def toByteBuffer(value: Array[Byte])                        = ByteBuffer.wrap(value)
  def toIndexedSeq(value: Array[Byte]): IndexedSeq[Byte]      = value
  def toSeq(value: Array[Byte]): Seq[Byte]                    = value
  def decodeString(value: Array[Byte], charset: Charset): Either[CharacterCodingException, String] =
    try Right(charset.newDecoder.decode(toByteBuffer(value)).toString)
    catch { case e: CharacterCodingException ⇒ Left(e) }

  ///////////////// ITERATION ///////////////////

  def foldLeft[A](value: Array[Byte], z: A, f: (A, Byte) ⇒ A)  = value.foldLeft(z)(f)
  def foldRight[A](value: Array[Byte], z: A, f: (Byte, A) ⇒ A) = value.foldRight(z)(f)
  def foreach(value: Array[Byte], f: Byte ⇒ Unit)              = value.foreach(f)

}

object ByteArrayBytes {

  val Empty = new Array[Byte](0)
}
