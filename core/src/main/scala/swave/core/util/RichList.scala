/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.util

import scala.annotation.tailrec

final class RichList[T](val underlying: List[T]) extends AnyVal {

  /**
    * Returns 0, 1 or 2 depending on the whether the list contains 0, 1 or more elements respectively.
    */
  def size012: Int =
    if (underlying.isEmpty) 0
    else if (underlying.tail.isEmpty) 1
    else 2

  /**
    * Reverses the list avoiding the extra allocation that is performed
    * by the default `list.reverse` if the list has one single element.
    */
  def fastReverse: List[T] =
    if (underlying.isEmpty || underlying.tail.isEmpty) underlying
    else underlying.reverse // does an unnecessary allocation for single-element lists

  /**
    * Removes the given element from the list and returns either a new list
    * or the identical (reference equality!) list if the element is not present.
    */
  def remove(elem: T): List[T] = {
    @tailrec def rec(revHead: List[T], tail: List[T]): List[T] =
      if (tail.nonEmpty) {
        if (tail.head == elem) revHead reverse_::: tail.tail
        else rec(tail.head :: revHead, tail.tail)
      } else underlying

    rec(Nil, underlying)
  }

}
