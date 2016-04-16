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

final class RichList[T](val underlying: List[T]) extends AnyVal {

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
