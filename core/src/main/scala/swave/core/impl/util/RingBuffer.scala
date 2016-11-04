/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.util

import scala.annotation.tailrec
import org.jctools.util.UnsafeAccess.UNSAFE
import org.jctools.util.UnsafeRefArrayAccess.calcElementOffset
import swave.core.macros._
import swave.core.util._

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
  def count: Int = writeIx - readIx

  /**
    * The number of elements the buffer can still take in.
    */
  def available: Int = capacity - count

  /**
    * True if the next write will succeed.
    */
  def canWrite: Boolean = capacity > count

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
    canWrite && {
      unsafeWrite(value)
      true
    }

  /**
    * Writes the given value into the buffer without any buffer overflow protection.
    */
  def unsafeWrite(value: T): Unit = {
    val w = writeIx
    UNSAFE.putObject(array, calcElementOffset((w & mask).toLong), value)
    writeIx = w + 1
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
    readIx = r + 1
    val ix  = calcElementOffset((r & mask).toLong)
    val res = UNSAFE.getObject(array, ix).asInstanceOf[T]
    UNSAFE.putObject(array, ix, null)
    res
  }

  /**
    * Drops the element that would otherwise be read next.
    * CAUTION: Must not be used if buffer is empty! This precondition is not verified!
    */
  def unsafeDropHead(): Unit = {
    val r = readIx
    UNSAFE.putObject(array, calcElementOffset((r & mask).toLong), null)
    readIx = r + 1
  }

  /**
    * Drops the element that was written last.
    * CAUTION: Must not be used if buffer is empty! This precondition is not verified!
    */
  def unsafeDropTail(): Unit = {
    val w = writeIx - 1
    UNSAFE.putObject(array, calcElementOffset((w & mask).toLong), null)
    writeIx = w
  }

  /**
    * Resets the buffer to "is empty" status and nulls out all references.
    */
  def clear(): Unit = {
    readIx = 0
    writeIx = 0
    java.util.Arrays.fill(array, null)
  }

  /**
    * Adds a traversable of elements to the buffer
    */
  def ++=(elems: Traversable[T]): Boolean = elems.forall(write)

  /**
    * Iterates (in FIFO order) over all elements currently in the buffer
    * changing neither the read- nor the write cursor.
    */
  def foreach[U](f: T => U): Unit = {
    @tailrec def rec(i: Int): Unit =
      if (i < writeIx) {
        val ix = calcElementOffset((i & mask).toLong)
        f(UNSAFE.getObject(array, ix).asInstanceOf[T])
        rec(i + 1)
      }
    rec(readIx)
  }

  override def toString: String = s"RingBuffer(len=${array.length}, size=$count, writeIx=$writeIx, readIx=$readIx)"
}
