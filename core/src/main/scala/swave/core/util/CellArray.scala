/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

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
