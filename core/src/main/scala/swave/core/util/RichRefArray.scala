/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.util

import scala.annotation.{switch, tailrec}
import shapeless._

final class RichRefArray[T <: AnyRef](val underlying: Array[T]) extends AnyVal {

  @tailrec def fastIndexOf(elem: AnyRef, from: Int = 0): Int =
    if (from < underlying.length) {
      if (elem eq underlying(from)) from
      else fastIndexOf(elem, from + 1)
    } else -1

  @tailrec def toHList(ix: Int = underlying.length - 1, suffix: HList = HNil): HList =
    if (ix >= 0) toHList(ix - 1, ::(underlying(ix), suffix))
    else suffix

  /**
    * In-place reverse
    */
  def reverse_!(): Unit = reverse_!(0, underlying.length)

  /**
    * In-place reverse of an array slice.
    */
  @tailrec def reverse_!(start: Int, end: Int): Unit = {
    val end1 = end - 1
    if (start < end1) {
      val h = underlying(start)
      underlying(start) = underlying(end1)
      underlying(end1) = h
      reverse_!(start + 1, end1)
    }
  }

  def foreachWithIndex(f: (T, Int) ⇒ Unit): Unit = {
    @tailrec def rec(ix: Int): Unit =
      if (ix < underlying.length) {
        f(underlying(ix), ix)
        rec(ix + 1)
      }
    rec(0)
  }

  def sumBy[N](f: T ⇒ N)(implicit num: Numeric[N]): N = {
    @tailrec def rec(ix: Int, sum: N): N =
      if (ix < underlying.length) rec(ix + 1, num.plus(sum, f(underlying(ix)))) else sum
    rec(0, num.zero)
  }

  def toTuple: AnyRef = {
    import underlying.{apply ⇒ a}
    (underlying.length: @switch) match {
      case 1  ⇒ Tuple1(a(0))
      case 2  ⇒ Tuple2(a(0), a(1))
      case 3  ⇒ Tuple3(a(0), a(1), a(2))
      case 4  ⇒ Tuple4(a(0), a(1), a(2), a(3))
      case 5  ⇒ Tuple5(a(0), a(1), a(2), a(3), a(4))
      case 6  ⇒ Tuple6(a(0), a(1), a(2), a(3), a(4), a(5))
      case 7  ⇒ Tuple7(a(0), a(1), a(2), a(3), a(4), a(5), a(6))
      case 8  ⇒ Tuple8(a(0), a(1), a(2), a(3), a(4), a(5), a(6), a(7))
      case 9  ⇒ Tuple9(a(0), a(1), a(2), a(3), a(4), a(5), a(6), a(7), a(8))
      case 10 ⇒ Tuple10(a(0), a(1), a(2), a(3), a(4), a(5), a(6), a(7), a(8), a(9))
      case 11 ⇒ Tuple11(a(0), a(1), a(2), a(3), a(4), a(5), a(6), a(7), a(8), a(9), a(10))
      case 12 ⇒ Tuple12(a(0), a(1), a(2), a(3), a(4), a(5), a(6), a(7), a(8), a(9), a(10), a(11))
      case 13 ⇒ Tuple13(a(0), a(1), a(2), a(3), a(4), a(5), a(6), a(7), a(8), a(9), a(10), a(11), a(12))
      case 14 ⇒ Tuple14(a(0), a(1), a(2), a(3), a(4), a(5), a(6), a(7), a(8), a(9), a(10), a(11), a(12), a(13))
      case 15 ⇒ Tuple15(a(0), a(1), a(2), a(3), a(4), a(5), a(6), a(7), a(8), a(9), a(10), a(11), a(12), a(13), a(14))
      case 16 ⇒
        Tuple16(a(0), a(1), a(2), a(3), a(4), a(5), a(6), a(7), a(8), a(9), a(10), a(11), a(12), a(13), a(14), a(15))
      case 17 ⇒
        Tuple17(
          a(0),
          a(1),
          a(2),
          a(3),
          a(4),
          a(5),
          a(6),
          a(7),
          a(8),
          a(9),
          a(10),
          a(11),
          a(12),
          a(13),
          a(14),
          a(15),
          a(16))
      case 18 ⇒
        Tuple18(
          a(0),
          a(1),
          a(2),
          a(3),
          a(4),
          a(5),
          a(6),
          a(7),
          a(8),
          a(9),
          a(10),
          a(11),
          a(12),
          a(13),
          a(14),
          a(15),
          a(16),
          a(17))
      case 19 ⇒
        Tuple19(
          a(0),
          a(1),
          a(2),
          a(3),
          a(4),
          a(5),
          a(6),
          a(7),
          a(8),
          a(9),
          a(10),
          a(11),
          a(12),
          a(13),
          a(14),
          a(15),
          a(16),
          a(17),
          a(18))
      case 20 ⇒
        Tuple20(
          a(0),
          a(1),
          a(2),
          a(3),
          a(4),
          a(5),
          a(6),
          a(7),
          a(8),
          a(9),
          a(10),
          a(11),
          a(12),
          a(13),
          a(14),
          a(15),
          a(16),
          a(17),
          a(18),
          a(19))
      case 21 ⇒
        Tuple21(
          a(0),
          a(1),
          a(2),
          a(3),
          a(4),
          a(5),
          a(6),
          a(7),
          a(8),
          a(9),
          a(10),
          a(11),
          a(12),
          a(13),
          a(14),
          a(15),
          a(16),
          a(17),
          a(18),
          a(19),
          a(20))
      case 22 ⇒
        Tuple22(
          a(0),
          a(1),
          a(2),
          a(3),
          a(4),
          a(5),
          a(6),
          a(7),
          a(8),
          a(9),
          a(10),
          a(11),
          a(12),
          a(13),
          a(14),
          a(15),
          a(16),
          a(17),
          a(18),
          a(19),
          a(20),
          a(21))
      case _ ⇒ throw new IllegalStateException("Cannot create tuple of size " + underlying.length)
    }
  }
}
