/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.util

import scala.reflect.ClassTag

final class CellWheel[T: ClassTag](val size: Int, createCell: ⇒ T) {
  requireArg(isPowerOf2(size))

  private[this] val array = Array.fill(size)(createCell)
  private[this] val mask = size - 1
  private[this] var index: Int = _

  def next(): T = {
    val result = array(index & mask)
    index += 1
    result
  }
}

sealed abstract class PrimitiveCell

final class DoubleCell extends PrimitiveCell {
  private[this] var _value: Double = _

  def put(value: Double): this.type = {
    if (_value != 0.asInstanceOf[Double]) throw new IllegalStateException("Cannot put into occupied cell")
    _value = value
    this
  }

  def extract(): Double = {
    val result = _value
    _value = 0.asInstanceOf[Double]
    result
  }
}
