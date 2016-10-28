/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.compat.scodec.impl

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, Charset}
import scala.collection.GenTraversableOnce
import scodec.bits.ByteVector
import swave.core.io.Bytes

class ByteVectorBytes extends Bytes[ByteVector] {

  ///////////////// CONSTRUCTION ///////////////////

  def empty                                               = ByteVector.empty
  def fill[A: Integral](size: Long)(byte: A)              = ByteVector.fill(size)(byte)
  def apply(array: Array[Byte])                           = ByteVector(array)
  def apply(bytes: Array[Byte], offset: Int, length: Int) = ByteVector(bytes, offset, length)
  def apply[A: Integral](bytes: A*)                       = ByteVector(bytes: _*)
  def apply(bytes: Vector[Byte])                          = ByteVector(bytes)
  def apply(buffer: ByteBuffer)                           = ByteVector(buffer)
  def apply(bs: GenTraversableOnce[Byte])                 = ByteVector(bs)
  def view(bytes: Array[Byte])                            = ByteVector(bytes)
  def view(bytes: ByteBuffer)                             = ByteVector(bytes)
  def encodeString(str: String, charset: Charset)         = if (str.isEmpty) empty else ByteVector(str getBytes charset)
  def encodeStringStrict(str: String, charset: Charset)   = ByteVector.encodeString(str)(charset)

  ///////////////// QUERY ///////////////////

  def size(value: ByteVector)                                           = value.size
  def byteAt(value: ByteVector, ix: Long)                               = value(ix)
  def indexOfSlice(value: ByteVector, slice: ByteVector, startIx: Long) = value.indexOfSlice(slice, startIx)

  ///////////////// TRANSFORMATION TO ByteVector ///////////////////

  def update(value: ByteVector, ix: Long, byte: Byte) = value.update(ix, byte)
  def concat(value: ByteVector, other: ByteVector)    = value ++ other
  def concat(value: ByteVector, byte: Byte)           = value :+ byte
  def concat(byte: Byte, value: ByteVector)           = byte +: value
  def drop(value: ByteVector, n: Long)                = value.drop(n)
  def take(value: ByteVector, n: Long)                = value.take(n)
  def map(value: ByteVector, f: Byte ⇒ Byte)          = value.map(f)
  def reverse(value: ByteVector)                      = value.reverse
  def compact(value: ByteVector)                      = value.compact

  ///////////////// TRANSFORMATION TO OTHER TYPES ///////////////////

  def toArray(value: ByteVector)                                   = value.toArray
  def copyToArray(value: ByteVector, xs: Array[Byte], offset: Int) = value.copyToArray(xs, offset)
  def copyToArray(value: ByteVector, sourceOffset: Long, xs: Array[Byte], destOffset: Int, len: Int) =
    value.copyToArray(xs, destOffset, sourceOffset, len)
  def copyToOutputStream(value: ByteVector, s: OutputStream) = value.copyToStream(s)
  def toByteBuffer(value: ByteVector)                        = value.toByteBuffer
  def toIndexedSeq(value: ByteVector): IndexedSeq[Byte]      = value.toIndexedSeq
  def toSeq(value: ByteVector): Seq[Byte]                    = value.toSeq
  def decodeString(value: ByteVector, charset: Charset): Either[CharacterCodingException, String] =
    value.decodeString(charset)

  ///////////////// ITERATION ///////////////////

  def foldLeft[A](value: ByteVector, z: A, f: (A, Byte) ⇒ A)  = value.foldLeft(z)(f)
  def foldRight[A](value: ByteVector, z: A, f: (Byte, A) ⇒ A) = value.foldRight(z)(f)
  def foreach(value: ByteVector, f: Byte ⇒ Unit)              = value.foreach(f)

}
