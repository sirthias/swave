/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.util

private[swave] class RichLong(private val underlying: Long) extends AnyVal {

  /**
    * Add with result bounded by [Long.MinValue, Long.MaxValue].
    */
  def ⊹(other: Int): Long = ⊹(other.toLong)

  /**
    * Add with result bounded by [Long.MinValue, Long.MaxValue].
    */
  def ⊹(other: Long): Long = {
    val r = underlying + other
    // HD 2-12 Overflow iff both arguments have the opposite sign of the result
    if (((underlying ^ r) & (other ^ r)) < 0) {
      if (r < 0) Long.MaxValue else Long.MinValue
    } else r
  }

  /**
    * Multiply with result bounded by [Long.MinValue, Long.MaxValue].
    */
  def ×(other: Int): Long = ×(other.toLong)

  /**
    * Multiply with result bounded by [Long.MinValue, Long.MaxValue].
    */
  def ×(other: Long): Long = {
    val r = underlying * other
    if (((math.abs(underlying) | math.abs(other)) >>> 31 != 0) &&
        (other != 0 && (r / other != underlying) || underlying == Long.MinValue && other == -1)) {
      if ((underlying ^ other) < 0) Long.MinValue else Long.MaxValue
    } else r
  }
}
