/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.util

import scala.annotation.tailrec

final class RichByteArray(val underlying: Array[Byte]) extends AnyVal {

  /**
   * Tests two byte arrays for value equality in a way that defends against timing attacks.
   * Simple equality testing will stop at the end of a matching prefix thereby leaking information
   * about the length of the matching prefix which can be exploited for per-byte progressive brute-forcing.
   *
   * @note This function leaks information about the length of each byte array as well as
   *       whether the two byte arrays have the same length.
   * @see [[http://codahale.com/a-lesson-in-timing-attacks/]]
   * @see [[http://rdist.root.org/2009/05/28/timing-attack-in-google-keyczar-library/]]
   * @see [[http://emerose.com/timing-attacks-explained]]
   */
  def secure_==(other: Array[Byte]): Boolean = {
    @tailrec def rec(ix: Int, result: Int): Int =
      if (ix < underlying.length) rec(ix + 1, result | (underlying(ix) ^ other(ix))) else result

    other.length == underlying.length && rec(0, 0) == 0
  }
}
