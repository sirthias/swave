/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.util

import org.jctools.util.UnsafeAccess.UNSAFE
import org.jctools.util.UnsafeRefArrayAccess.calcElementOffset
import scala.annotation.tailrec
import swave.core.impl.util.MultiReaderRingBuffer._
import swave.core.macros._
import swave.core.util._

/**
  * INTERNAL API
  * A fixed-size mutable RingBuffer that supports multiple readers.
  */
private[swave] class MultiReaderRingBuffer[T](cap: Int) {
  requireArg(isPowerOf2(cap) && cap > 0) // automatically implies cap <= 0x40000000

  private[this] val array = new Array[AnyRef](cap)
  private[this] def mask  = array.length - 1 // bit mask for converting a cursor into an array index

  /*
   * two counters counting the number of elements ever written and read; wrap-around is
   * handled by always looking at differences or masked values
   */
  private[this] var writeIx = 0
  private[this] var readIx  = 0 // the "oldest" of all read cursor indices, i.e. the one that is most behind

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
    * Returns the number of elements that the buffer currently contains for the given cursor.
    */
  def canReadCount(cursor: Cursor): Int = writeIx - cursor.cursor

  /**
    * True if the given cursor can read at least one element.
    */
  def canRead(cursor: Cursor): Boolean = writeIx != cursor.cursor

  /**
    * Initializes the given Cursor to the oldest buffer entry that is still available.
    */
  def initCursor(cursor: Cursor): Unit = cursor.cursor = readIx

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
    * Returns the next element from the buffer for the given cursor and moves the cursor forward.
    * CAUTION: This method does NOT check whether the cursor still has elements to read!
    * Make sure to call `canRead` is true before calling this method!
    */
  def unsafeRead(cursor: Cursor, firstCursor: Cursor): T = {
    val c = cursor.cursor
    cursor.cursor = c + 1
    val res = UNSAFE.getObject(array, calcElementOffset((c & mask).toLong))
    if (c == readIx) updateReadIx(firstCursor)
    res.asInstanceOf[T]
  }

  /**
    * If a cursor is removed this method needs to be called in order to release the cursor.
    * @param cursor the cursor that was deleted
    * @param firstCursor the head of the cursor chain which must not contain the removed cursor anymore
    */
  def releaseCursor(cursor: Cursor, firstCursor: Cursor): Unit =
    if (cursor.cursor == readIx) // if this cursor is the last one it must be at readIx
      updateReadIx(firstCursor)

  private def updateReadIx(firstCursor: Cursor): Unit = {
    @tailrec def minCursor(c: Cursor, result: Int): Int =
      if (c ne null) minCursor(c.tail, math.min(c.cursor - writeIx, result)) else result
    val newReadIx = writeIx + minCursor(firstCursor, 0)
    @tailrec def zeroOut(r: Int): Int =
      if (r != newReadIx) {
        UNSAFE.putObject(array, calcElementOffset((r & mask).toLong), null)
        zeroOut(r + 1)
      } else r
    readIx = zeroOut(readIx)
  }

  override def toString: String =
    s"MultiReaderRingBuffer(len=${array.length}, size=$count, writeIx=$writeIx, readIx=$readIx)"
}

/**
  * INTERNAL API
  */
private[swave] object MultiReaderRingBuffer {

  trait Cursor {
    def cursor: Int
    def cursor_=(ix: Int): Unit
    def tail: Cursor // the next cursor or null
  }
}
