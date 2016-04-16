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

final class IntInt(val longValue: Long) extends AnyVal {
  def _1: Int = (longValue >>> 32).toInt
  def _2: Int = (longValue & 0xFFFFFFFF).toInt

  def toTuple: (Int, Int) = (_1, _2)

  // extraction support
  def isEmpty: Boolean = false
  def get: (Int, Int) = toTuple
}

object IntInt {
  def apply(i1: Int, i2: Int): IntInt = new IntInt(i1.toLong << 32 | i2.toLong)

  def unapply(ii: IntInt): IntInt = ii
}

