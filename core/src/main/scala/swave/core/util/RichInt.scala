/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.util

import scala.annotation.tailrec
import swave.core.macros._

private[swave] class RichInt(private val underlying: Int) extends AnyVal {

  def times(block: ⇒ Unit): Unit = {
    requireArg(underlying >= 0, s"`$underlying.times(...)` is illegal")
    @tailrec def rec(i: Int): Unit =
      if (i > 0) {
        block
        rec(i - 1)
      }
    rec(underlying)
  }
}
