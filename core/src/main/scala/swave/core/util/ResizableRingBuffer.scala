/*
 * Copyright © 2016 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swave.core.util

import org.jctools.util.UnsafeAccess.UNSAFE
import org.jctools.util.UnsafeRefArrayAccess.calcElementOffset

/**
 * A mutable RingBuffer that can grow in size.
 * Contrary to many other ring buffer implementations this one does not automatically overwrite the oldest
 * elements, rather, if full, the buffer tries to grow and rejects further writes if max capacity is reached.
 */
private[swave] final class ResizableRingBuffer[T <: AnyRef](initialCap: Int, maxCap: Int) {
  requireArg(isPowerOf2(maxCap) && maxCap > 0) // automatically implies maxCap <= 0x40000000
  requireArg(isPowerOf2(initialCap) && 0 < initialCap && initialCap <= maxCap)

  private[this] var array = new Array[AnyRef](initialCap)
  private[this] var mask = array.length - 1 // bit mask for converting a cursor into an array index

  /*
   * two counters counting the number of elements ever written and read; wrap-around is
   * handled by always looking at differences or masked values
   */
  private[this] var writeIx = 0
  private[this] var readIx = 0

  /**
   * The number of elements currently in the buffer.
   */
  def size: Int = writeIx - readIx

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
    if (size < currentCapacity) { // if we have space left we can simply write and be done
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
    if (size < currentCapacity - 1) { // if we have space left we can simply write and be done
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
    if (size < currentCapacity - 2) { // if we have space left we can simply write and be done
      val w = writeIx
      UNSAFE.putObject(array, calcElementOffset((w & mask).toLong), v1)
      UNSAFE.putObject(array, calcElementOffset(((w + 1) & mask).toLong), v2)
      UNSAFE.putObject(array, calcElementOffset(((w + 2) & mask).toLong), v3)
      writeIx = w + 3
      true
    } else grow() && write(v1, v2, v3)

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
    UNSAFE.getObject(array, calcElementOffset((r & mask).toLong)).asInstanceOf[T]
  }

  private def grow(): Boolean =
    (array.length < maxCap) && {
      val r = readIx & mask
      val newArray = new Array[AnyRef](array.length << 1)
      System.arraycopy(array, r, newArray, 0, array.length - r)
      System.arraycopy(array, 0, newArray, array.length - r, r)
      array = newArray
      mask = newArray.length - 1
      writeIx = size
      readIx = 0
      true
    }

  override def toString: String =
    s"ResizableRingBuffer(len=${array.length}, size=$size, writeIx=$writeIx, readIx=$readIx)"
}
