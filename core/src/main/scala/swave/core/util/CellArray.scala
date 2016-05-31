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

import scala.annotation.tailrec
import scala.collection.generic.CanBuildFrom

final class CellArray[T](val size: Int) {
  private val array = new Array[AnyRef](size)

  def update(ix: Int, value: T): Unit = {
    if (array(ix) ne null) throw new IllegalStateException("Cannot put into occupied cell at index " + ix)
    array(ix) = value.asInstanceOf[AnyRef]
  }

  def apply(ix: Int): T = {
    val result = array(ix).asInstanceOf[T]
    array(ix) = null
    result
  }

  def copyOf(length: Int): CellArray[T] = {
    val result = new CellArray[T](length)
    System.arraycopy(array, 0, result.array, 0, length)
    result
  }

  def copyOfRange(from: Int, to: Int): CellArray[T] = {
    val len = to - from
    val result = new CellArray[T](len)
    System.arraycopy(array, from, result.array, 0, len)
    result
  }

  def toSeq[M[+_]](implicit cbf: CanBuildFrom[M[T], T, M[T]]): M[T] = {
    val builder = cbf()
    @tailrec def rec(ix: Int): Unit = if (ix < size) { builder += this(ix); rec(ix + 1) }
    rec(0)
    builder.result()
  }

  override def toString: String = toSeq[List].mkString("CellArray(", ", ", ")")
}
