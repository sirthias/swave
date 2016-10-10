/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.util

import scala.annotation.tailrec
import scala.collection.mutable

final class RichArrayBuffer[A](val underlying: mutable.ArrayBuffer[A]) extends AnyVal {

  def inplaceSortBy[B](f: A ⇒ B)(implicit ord: Ordering[B]): Unit = {
    val buf   = underlying.asInstanceOf[mutable.ArrayBuffer[AnyRef]]
    val array = buf.toArray
    java.util.Arrays.sort(array, ord.on(f).asInstanceOf[Ordering[AnyRef]])
    buf.clear()
    buf ++= array
    ()
  }

  def removeWhere(f: A ⇒ Boolean): Unit = {
    @tailrec def rec(ix: Int): Unit =
      if (ix >= 0) {
        if (f(underlying(ix))) underlying.remove(ix)
        rec(ix - 1)
      }
    rec(underlying.size - 1)
  }

  def removeIfPresent(elem: A): Unit =
    underlying.indexOf(elem) match {
      case -1 ⇒
      case ix ⇒ { underlying.remove(ix); () }
    }
}
