/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.compat.akka.impl

import java.io.OutputStream
import java.nio.charset.{CharacterCodingException, Charset}
import java.nio.{ByteBuffer, CharBuffer}
import java.util
import scala.annotation.tailrec
import scala.collection.GenTraversableOnce
import akka.util.ByteString
import swave.core.io.Bytes
import swave.core.macros._

class ByteStringBytes extends Bytes[ByteString] {

  ///////////////// CONSTRUCTION ///////////////////

  def empty = ByteString.empty
  def fill[A: Integral](size: Long)(byte: A) = {
    requireArg(0 <= size && size <= Int.MaxValue, "`n` must be >= 0 and <= Int.MaxValue")
    val b = implicitly[Integral[A]].toInt(byte).toByte
    apply(Array.fill(size.toInt)(b))
  }
  def apply(array: Array[Byte]) = ByteString(array)
  def apply(bytes: Array[Byte], offset: Int, length: Int) =
    ByteString(util.Arrays.copyOfRange(bytes, offset, offset + length))
  def apply[A: Integral](bytes: A*) = {
    val integral = implicitly[Integral[A]]
    val buf      = new Array[Byte](bytes.size)
    @tailrec def rec(ix: Int): ByteString =
      if (ix < buf.length) {
        buf(ix) = integral.toInt(bytes(ix)).toByte
        rec(ix + 1)
      } else view(buf)
    rec(0)
  }
  def apply(bytes: Vector[Byte])                  = ByteString(bytes: _*)
  def apply(buffer: ByteBuffer)                   = ByteString(buffer)
  def apply(bs: GenTraversableOnce[Byte])         = ByteString(bs.toArray)
  def view(bytes: Array[Byte])                    = ByteString(bytes) // no view-like constructor available on ByteStrings
  def view(bytes: ByteBuffer)                     = ByteString(bytes) // no view-like constructor available on ByteStrings
  def encodeString(str: String, charset: Charset) = ByteString(str, charset.name)
  def encodeStringStrict(str: String, charset: Charset) =
    try Right(ByteString(charset.newEncoder.encode(CharBuffer.wrap(str))))
    catch { case e: CharacterCodingException ⇒ Left(e) }

  ///////////////// QUERY ///////////////////

  def size(value: ByteString) = value.size.toLong
  def byteAt(value: ByteString, ix: Long) = {
    requireArg(0 <= ix && ix <= Int.MaxValue, "`n` must be >= 0 and <= Int.MaxValue")
    value(ix.toInt)
  }
  def indexOfSlice(value: ByteString, slice: ByteString, startIx: Long) = ??? // TODO

  ///////////////// TRANSFORMATION TO ByteString ///////////////////

  def update(value: ByteString, ix: Long, byte: Byte) = concat(concat(take(value, ix), byte), drop(value, ix + 1))
  def concat(value: ByteString, other: ByteString)    = value ++ other
  def concat(value: ByteString, byte: Byte)           = value ++ ByteString(byte)
  def concat(byte: Byte, value: ByteString)           = ByteString(byte) ++ value
  def drop(value: ByteString, n: Long) = {
    requireArg(0 <= n && n <= Int.MaxValue, "`n` must be >= 0 and <= Int.MaxValue")
    value.drop(n.toInt)
  }
  def take(value: ByteString, n: Long) = {
    requireArg(0 <= n && n <= Int.MaxValue, "`n` must be >= 0 and <= Int.MaxValue")
    value.take(n.toInt)
  }
  def map(value: ByteString, f: Byte ⇒ Byte) = value.map(f)
  def reverse(value: ByteString)             = value.reverse
  def compact(value: ByteString)             = value.compact

  ///////////////// TRANSFORMATION TO OTHER TYPES ///////////////////

  def toArray(value: ByteString)                                   = value.toArray
  def copyToArray(value: ByteString, xs: Array[Byte], offset: Int) = value.copyToArray(xs, offset)
  def copyToArray(value: ByteString, sourceOffset: Long, xs: Array[Byte], destOffset: Int, len: Int) =
    drop(value, sourceOffset).copyToArray(xs, destOffset, len)
  def copyToOutputStream(value: ByteString, s: OutputStream) = ??? // TODO
  def toByteBuffer(value: ByteString)                        = value.toByteBuffer
  def toIndexedSeq(value: ByteString): IndexedSeq[Byte]      = value
  def toSeq(value: ByteString): Seq[Byte]                    = value
  def decodeString(value: ByteString, charset: Charset): Either[CharacterCodingException, String] =
    try Right(charset.newDecoder.decode(toByteBuffer(value)).toString)
    catch { case e: CharacterCodingException ⇒ Left(e) }

  ///////////////// ITERATION ///////////////////

  def foldLeft[A](value: ByteString, z: A, f: (A, Byte) ⇒ A)  = value.foldLeft(z)(f)
  def foldRight[A](value: ByteString, z: A, f: (Byte, A) ⇒ A) = value.foldRight(z)(f)
  def foreach(value: ByteString, f: Byte ⇒ Unit)              = value.foreach(f)

}
