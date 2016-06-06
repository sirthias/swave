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