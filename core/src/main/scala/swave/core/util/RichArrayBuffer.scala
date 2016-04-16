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

import scala.annotation.tailrec
import scala.collection.mutable

final class RichArrayBuffer[A](val underlying: mutable.ArrayBuffer[A]) extends AnyVal {

  def inplaceSortBy[B](f: A ⇒ B)(implicit ord: Ordering[B]): Unit = {
    val buf = underlying.asInstanceOf[mutable.ArrayBuffer[AnyRef]]
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
