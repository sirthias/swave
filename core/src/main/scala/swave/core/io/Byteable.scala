/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.io

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.{ Charset, CharacterCodingException }
import scala.collection.GenTraversableOnce
import swave.core.util._

/**
 * Typeclass qualifying `T` as a type holding one or more bytes.
 *
 * Used as an abstraction across popular "byte holder types" like
 * `akka.util.ByteString` or `scodec.bits.ByteVector`.
 *
 * API surface is modelled after `scodec.bits.ByteVector`.
 */
trait Byteable[T <: AnyRef] {

  ///////////////// CONSTRUCTION ///////////////////

  def empty: T
  def fill[A: Integral](size: Long)(b: A): T
  def apply(array: Array[Byte]): T
  def apply(bytes: Array[Byte], offset: Int, length: Int): T
  def apply[A: Integral](bytes: A*): T // uses only the least significant byte each integral value
  def apply(bytes: Vector[Byte]): T
  def apply(buffer: ByteBuffer): T
  def apply(bs: GenTraversableOnce[Byte]): T
  def view(bytes: Array[Byte]): T
  def view(bytes: ByteBuffer): T

  // encode replacing malformed-input and unmappable-characters with the charset's default replacement byte array
  def encodeString(str: String, charset: Charset): T

  // encode in a way that returns an error when malformed-input or unmappable-characters are encountered
  def encodeStringStrict(str: String, charset: Charset): Either[CharacterCodingException, T]

  ///////////////// QUERY ///////////////////

  def size(value: T): Long
  def byteAt(value: T, ix: Long): Byte
  def indexOfSlice(value: T, slice: T, startIx: Long): Long

  ///////////////// TRANSFORMATION TO T ///////////////////

  def update(value: T, ix: Long, byte: Byte): T
  def concat(value: T, other: T): T
  def concat(value: T, byte: Byte): T
  def concat(b: Byte, value: T): T
  def drop(value: T, n: Long): T
  def take(value: T, n: Long): T
  def map(value: T, f: Byte ⇒ Byte): T
  def reverse(value: T): T
  def compact(value: T): T

  ///////////////// TRANSFORMATION TO OTHER TYPES ///////////////////

  def toArray(value: T): Array[Byte]
  def copyToArray(value: T, xs: Array[Byte], offset: Int): Unit
  def copyToArray(value: T, sourceOffset: Long, xs: Array[Byte], destOffset: Int, len: Int): Unit
  def copyToOutputStream(value: T, s: OutputStream): Unit
  def toByteBuffer(value: T): ByteBuffer
  def toIndexedSeq(value: T): IndexedSeq[Byte]
  def toSeq(value: T): Seq[Byte]
  def decodeString(value: T, charset: Charset): Either[CharacterCodingException, String]

  ///////////////// ITERATION ///////////////////

  def foldLeft[A](value: T, z: A, f: (A, Byte) ⇒ A): A
  def foldRight[A](value: T, z: A, f: (Byte, A) ⇒ A): A
  def foreach(value: T, f: Byte ⇒ Unit): Unit
}

object Byteable {

  def decorator[T <: AnyRef](value: T): Decorator[T] = new Decorator(value)

  class Decorator[T <: AnyRef](val value: T) extends AnyVal {
    private implicit def decorate(x: T): Decorator[T] = decorator(x)

    def size(implicit b: Byteable[T]): Long = b.size(value)
    def intSize(implicit b: Byteable[T]): Option[Int] = {
      val s = size
      if (s <= Int.MaxValue) Some(s.toInt) else None
    }

    def isEmpty(implicit b: Byteable[T]): Boolean = size == 0
    def nonEmpty(implicit b: Byteable[T]): Boolean = size != 0

    def get(ix: Long)(implicit b: Byteable[T]): Byte = apply(ix)
    def apply(ix: Long)(implicit b: Byteable[T]): Byte = b.byteAt(value, ix)

    def lift(ix: Long)(implicit b: Byteable[T]): Option[Byte] = {
      if (0 <= ix && ix < size) Some(apply(ix))
      else None
    }

    def update(ix: Long, byte: Byte)(implicit b: Byteable[T]): T = b.update(value, ix, byte)
    def insert(ix: Long, byte: Byte)(implicit b: Byteable[T]): T = (take(ix) :+ byte) ++ drop(ix)
    def splice(ix: Long, other: T)(implicit b: Byteable[T]): T = take(ix) ++ other ++ drop(ix)
    def patch(ix: Long, other: T)(implicit b: Byteable[T]): T = take(ix) ++ other ++ drop(ix + other.size)

    def ++(other: T)(implicit b: Byteable[T]): T = b.concat(value, other)
    def +:(byte: Byte)(implicit b: Byteable[T]): T = b.concat(byte, value)
    def :+(byte: Byte)(implicit b: Byteable[T]): T = b.concat(value, byte)

    def drop(n: Long)(implicit b: Byteable[T]): T = b.drop(value, n)
    def dropRight(n: Long)(implicit b: Byteable[T]): T = take(size - n.max(0))
    def take(n: Long)(implicit b: Byteable[T]): T = b.take(value, n)
    def takeRight(n: Long)(implicit b: Byteable[T]): T = drop(size - n.max(0))

    def splitAt(n: Long)(implicit b: Byteable[T]): (T, T) = (take(n), drop(n))
    def slice(startIx: Long, endIx: Long)(implicit b: Byteable[T]): T = drop(startIx).take(endIx - startIx)

    def foldLeft[A](z: A)(f: (A, Byte) ⇒ A)(implicit b: Byteable[T]): A = b.foldLeft(value, z, f)
    def foldRight[A](z: A)(f: (Byte, A) ⇒ A)(implicit b: Byteable[T]): A = b.foldRight(value, z, f)

    def foreach(f: Byte ⇒ Unit)(implicit b: Byteable[T]): Unit = b.foreach(value, f)

    def startsWith(other: T)(implicit b: Byteable[T]): Boolean = take(other.size) == other
    def endsWith(other: T)(implicit b: Byteable[T]): Boolean = takeRight(other.size) == b

    def indexOfSlice(slice: T)(implicit b: Byteable[T]): Long = indexOfSlice(slice, 0)
    def indexOfSlice(slice: T, startIx: Long)(implicit b: Byteable[T]): Long = b.indexOfSlice(value, slice, startIx)

    def containsSlice(slice: T)(implicit b: Byteable[T]): Boolean = indexOfSlice(slice) >= 0

    def head(implicit b: Byteable[T]): Byte = apply(0)
    def headOption(implicit b: Byteable[T]): Option[Byte] = lift(0)

    def tail(implicit b: Byteable[T]): T = drop(1)
    def init(implicit b: Byteable[T]): T = dropRight(1)
    def last(implicit b: Byteable[T]): Byte = apply(size - 1)
    def lastOption(implicit b: Byteable[T]): Option[Byte] = lift(size - 1)

    def padRight(n: Long)(implicit b: Byteable[T]): T =
      if (n < size) throw new IllegalArgumentException(s"ByteVector.padRight($n)") else this ++ b.fill(n - size)(0)
    def padLeft(n: Long)(implicit b: Byteable[T]): T =
      if (n < size) throw new IllegalArgumentException(s"ByteVector.padLeft($n)") else b.fill(n - size)(0) ++ value

    def map(f: Byte ⇒ Byte)(implicit b: Byteable[T]): T = b.map(value, f)
    def mapI(f: Byte ⇒ Int)(implicit b: Byteable[T]): T = map(f andThen { _.toByte })

    def reverse(implicit b: Byteable[T]): T = b.reverse(value)
    def compact(implicit b: Byteable[T]): T = b.compact(value)

    def toArray(implicit b: Byteable[T]): Array[Byte] = b.toArray(value)
    def copyToArray(xs: Array[Byte], offset: Int)(implicit b: Byteable[T]): Unit = b.copyToArray(value, xs, offset)
    def copyToArray(sourceOffset: Long, xs: Array[Byte], destOffset: Int, len: Int)(implicit b: Byteable[T]): Unit =
      b.copyToArray(value, sourceOffset, xs, destOffset, len)

    def copyToOutputStream(s: OutputStream)(implicit b: Byteable[T]): Unit = b.copyToOutputStream(value, s)
    def toByteBuffer(implicit b: Byteable[T]): ByteBuffer = b.toByteBuffer(value)

    def toIndexedSeq(implicit b: Byteable[T]): IndexedSeq[Byte] = b.toIndexedSeq(value)
    def toSeq(implicit b: Byteable[T]): Seq[Byte] = toIndexedSeq

    def decodeString(charset: Charset)(implicit b: Byteable[T]): Either[CharacterCodingException, String] =
      b.decodeString(value, charset)
    def decodeUtf8(implicit b: Byteable[T]): Either[CharacterCodingException, String] = decodeString(UTF8)
    def decodeAscii(implicit b: Byteable[T]): Either[CharacterCodingException, String] = decodeString(ASCII)
  }

}
