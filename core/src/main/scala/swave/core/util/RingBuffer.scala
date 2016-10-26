/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.util

import swave.core.macros._
import scala.annotation.tailrec

/**
  * A mutable RingBuffer with a fixed capacity.
  * The `cap` must be a positive power of two.
  */
private[swave] final class RingBuffer[T](cap: Int) {
  requireArg(isPowerOf2(cap) && cap > 0) // automatically implies cap <= 0x40000000

  private[this] val array = new Array[AnyRef](cap)
  private[this] def mask  = array.length - 1 // bit mask for converting a cursor into an array index

  /*
   * two counters counting the number of elements ever written and read; wrap-around is
   * handled by always looking at differences or masked values
   */
  private[this] var writeIx = 0
  private[this] var readIx  = 0

  /**
    * The maximum number of elements the buffer can hold.
    */
  def capacity: Int = array.length

  /**
    * The number of elements currently in the buffer.
    */
  def size: Int = writeIx - readIx

  /**
    * The number of elements the buffer can still take in.
    */
  def available: Int = capacity - size

  /**
    * True if the next write will succeed.
    */
  def canWrite: Boolean = capacity > size

  /**
    * True if no elements are currently in the buffer.
    */
  def isEmpty: Boolean = writeIx == readIx

  /**
    * True if at least one elements is currently in the buffer.
    */
  def nonEmpty: Boolean = writeIx != readIx

  /**
    * Tries to write the given value into the buffer.
    * Returns `true` if the write was successful and false if the buffer is full.
    */
  def write(value: T): Boolean =
    (size < capacity) && {
      unsafeWrite(value)
      true
    }

  /**
    * Writes the given value into the buffer without any buffer overflow protection.
    */
  def unsafeWrite(value: T): Unit = {
    array(writeIx & mask) = value.asInstanceOf[AnyRef]
    writeIx += 1
  }

  /**
    * Reads the next value from the buffer.
    * Throws a NoSuchElementException if the buffer is empty.
    */
  def read(): T =
    if (nonEmpty) unsafeRead()
    else throw new NoSuchElementException

  /**
    * Reads the next value from the buffer without any buffer underrun protection.
    */
  def unsafeRead(): T = {
    val r = readIx
    readIx += 1
    array(r & mask).asInstanceOf[T]
  }

  /**
    * Drops the element that would otherwise be read next.
    * CAUTION: Must not be used if buffer is empty! This precondition is not verified!
    */
  def unsafeDropHead(): Unit = readIx += 1

  /**
    * Drops the element that was written last.
    * CAUTION: Must not be used if buffer is empty! This precondition is not verified!
    */
  def unsafeDropTail(): Unit = writeIx -= 1

  /**
    * Resets the buffer to "is empty" status without nulling out references.
    */
  def softClear(): Unit = {
    readIx = 0
    writeIx = 0
  }

  /**
    * Resets the buffer to "is empty" status and nulls out all references.
    */
  def hardClear(): Unit = {
    softClear()
    java.util.Arrays.fill(array, null)
  }

  /**
    * Adds a traversable of elements to the buffer
    * @param elems
    */
  def ++=(elems: Traversable[T]): Boolean = elems.forall(write)

  /**
    * Iterates the underlying elements of the buffer
    *
    */
  def foreach[U](f: T => U): Unit = {
    @tailrec def rec(i: Int): Unit =
      if (i < writeIx) {
        f(array(i & mask).asInstanceOf[T])
        rec(i + 1)
      }
    rec(readIx)
  }

  override def toString: String = s"RingBuffer(len=${array.length}, size=$size, writeIx=$writeIx, readIx=$readIx)"
}
