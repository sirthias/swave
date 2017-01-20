/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.util

import org.jctools.util.UnsafeAccess.UNSAFE
import org.jctools.util.UnsafeRefArrayAccess.calcElementOffset
import swave.core.macros._
import swave.core.util._

/**
  * A mutable RingBuffer that can grow in size.
  * Contrary to many other ring buffer implementations this one does not automatically overwrite the oldest
  * elements, rather, if full, the buffer tries to grow and rejects further writes if max capacity is reached.
  */
private[swave] final class ResizableRingBuffer[T](initialCap: Int, maxCap: Int) {
  requireArg(isPowerOf2(maxCap) && maxCap > 0) // automatically implies maxCap <= 0x40000000
  requireArg(isPowerOf2(initialCap) && 0 < initialCap && initialCap <= maxCap)

  private[this] var array = new Array[AnyRef](initialCap)
  private[this] var mask  = array.length - 1 // bit mask for converting a cursor into an array index

  /*
   * two counters counting the number of elements ever written and read; wrap-around is
   * handled by always looking at differences or masked values
   */
  private[this] var writeIx = 0
  private[this] var readIx  = 0

  /**
    * The number of elements currently in the buffer.
    */
  def count: Int = writeIx - readIx

  /**
    * True if no elements are currently in the buffer.
    */
  def isEmpty: Boolean = writeIx == readIx

  /**
    * True if at least one elements is currently in the buffer.
    */
  def nonEmpty: Boolean = writeIx != readIx

  /**
    * The number of elements the buffer can hold without having to be resized.
    */
  def currentCapacity: Int = array.length

  /**
    * The maximum number of elements the buffer can hold.
    */
  def maxCapacity: Int = maxCap

  /**
    * Tries to write the given value into the buffer thereby potentially growing the backing array.
    * Returns `true` if the write was successful and false if the buffer is full and cannot grow anymore.
    */
  def write(value: T): Boolean =
    if (count < currentCapacity) { // if we have space left we can simply write and be done
      val w = writeIx
      UNSAFE.putObject(array, calcElementOffset((w & mask).toLong), value)
      writeIx = w + 1
      true
    } else grow() && write(value)

  /**
    * Tries to write the given values into the buffer thereby potentially growing the backing array.
    * Returns `true` if the write was successful and false if the buffer is full and cannot grow anymore.
    */
  def write(v1: T, v2: T): Boolean =
    if (count < currentCapacity - 1) { // if we have space left we can simply write and be done
      val w = writeIx
      UNSAFE.putObject(array, calcElementOffset((w & mask).toLong), v1)
      UNSAFE.putObject(array, calcElementOffset(((w + 1) & mask).toLong), v2)
      writeIx = w + 2
      true
    } else grow() && write(v1, v2)

  /**
    * Tries to write the given values into the buffer thereby potentially growing the backing array.
    * Returns `true` if the write was successful and false if the buffer is full and cannot grow anymore.
    */
  def write(v1: T, v2: T, v3: T): Boolean =
    if (count < currentCapacity - 2) { // if we have space left we can simply write and be done
      val w = writeIx
      UNSAFE.putObject(array, calcElementOffset((w & mask).toLong), v1)
      UNSAFE.putObject(array, calcElementOffset(((w + 1) & mask).toLong), v2)
      UNSAFE.putObject(array, calcElementOffset(((w + 2) & mask).toLong), v3)
      writeIx = w + 3
      true
    } else grow() && write(v1, v2, v3)

  /**
    * Tries to write the given values into the buffer thereby potentially growing the backing array.
    * Returns `true` if the write was successful and false if the buffer is full and cannot grow anymore.
    */
  def write(v1: T, v2: T, v3: T, v4: T): Boolean =
    if (count < currentCapacity - 3) { // if we have space left we can simply write and be done
      val w = writeIx
      UNSAFE.putObject(array, calcElementOffset((w & mask).toLong), v1)
      UNSAFE.putObject(array, calcElementOffset(((w + 1) & mask).toLong), v2)
      UNSAFE.putObject(array, calcElementOffset(((w + 2) & mask).toLong), v3)
      UNSAFE.putObject(array, calcElementOffset(((w + 3) & mask).toLong), v4)
      writeIx = w + 4
      true
    } else grow() && write(v1, v2, v3, v4)

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

  private def grow(): Boolean =
    (array.length < maxCap) && {
      val r        = readIx & mask
      val newArray = new Array[AnyRef](array.length << 1)
      System.arraycopy(array, r, newArray, 0, array.length - r)
      System.arraycopy(array, 0, newArray, array.length - r, r)
      array = newArray
      mask = newArray.length - 1
      writeIx = count
      readIx = 0
      true
    }

  override def toString: String =
    s"ResizableRingBuffer(len=${array.length}, size=$count, writeIx=$writeIx, readIx=$readIx)"
}
