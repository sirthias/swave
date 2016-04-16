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
