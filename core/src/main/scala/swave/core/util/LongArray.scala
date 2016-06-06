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

private[swave] final class LongArray private (private val array: Array[Long]) extends AnyVal {

  def size: Int = array(0).toInt

  def apply(ix: Int): Long = array(ix + 1)

  def +=(elem: Long): LongArray = {
    val size = this.size
    val size1 = size + 1
    val a =
      if (size1 < array.length) array
      else if (array.length == Int.MaxValue) sys.error("LongArray capacity overflow")
      else {
        val newLen = if (size < (1 << 30)) array.length << 1 else Int.MaxValue
        val newArray = new Array[Long](newLen)
        System.arraycopy(array, 1, newArray, 1, size)
        newArray
      }
    a(0) = size1.toLong
    a(size1) = elem
    new LongArray(a)
  }

  def removeAt(ix: Int): LongArray = {
    val size = this.size
    if (ix < size) {
      val size1 = size - 1
      if (ix < size1) System.arraycopy(array, ix + 2, array, ix + 1, size1 - ix)
      array(0) = size1.toLong
      this
    } else throw new NoSuchElementException(s"index ($ix) >= size ($size)")
  }

  def toArray: Array[Long] = {
    val size = this.size
    val newArray = new Array[Long](size)
    System.arraycopy(array, 1, newArray, 0, size)
    newArray
  }
}

object LongArray {

  def apply(initialCapacity: Int = 15): LongArray = {
    requireArg(initialCapacity >= 0)
    new LongArray(new Array[Long](initialCapacity + 1))
  }
}
