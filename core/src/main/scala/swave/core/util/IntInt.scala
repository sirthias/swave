/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.util

final class IntInt(val longValue: Long) extends AnyVal {
  def _1: Int = (longValue >>> 32).toInt
  def _2: Int = (longValue & 0xFFFFFFFF).toInt

  def toTuple: (Int, Int) = (_1, _2)

  // extraction support
  def isEmpty: Boolean = false
  def get: (Int, Int)  = toTuple
}

object IntInt {
  def apply(i1: Int, i2: Int): IntInt = new IntInt(i1.toLong << 32 | i2.toLong)

  def unapply(ii: IntInt): IntInt = ii
}
