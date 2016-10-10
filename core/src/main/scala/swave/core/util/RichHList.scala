/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.util

import scala.annotation.tailrec
import shapeless._

import scala.collection.mutable.ListBuffer

final class RichHList[L <: HList](val underlying: L) extends AnyVal {

  def toUntypedList: List[Any] = {
    val buf = new ListBuffer[Any]
    @tailrec def rec(remaining: HList): List[Any] =
      remaining match {
        case HNil ⇒ buf.toList
        case head :: tail ⇒
          buf += head
          rec(tail)
      }
    rec(underlying)
  }

}
