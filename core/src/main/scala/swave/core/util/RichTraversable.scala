/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.util

final class RichTraversable[A](val underlying: Traversable[A]) extends AnyVal {

  def sumBy[N](f: A ⇒ N)(implicit num: Numeric[N]): N =
    underlying.foldLeft(num.zero)((sum, x) ⇒ num.plus(sum, f(x)))
}
