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
import scala.collection.mutable.ListBuffer

/**
 * Intrusive, mutable, single-linked list.
 */
private[swave] abstract class ImsiList[L >: Null <: ImsiList[L]]( final var tail: L)

private[swave] object ImsiList {

  implicit class ImsiListOps[L >: Null <: ImsiList[L]](private val underlying: L) extends AnyVal {

    def isEmpty: Boolean = underlying eq null
    def nonEmpty: Boolean = underlying ne null

    def size: Int = {
      @tailrec def rec(current: L, count: Int): Int =
        if (current ne null) rec(current.tail, count + 1) else count
      rec(underlying, 0)
    }

    def last: L = {
      @tailrec def rec(last: L, current: L): L =
        if (current ne null) rec(current, current.tail) else last
      if (nonEmpty) rec(underlying, underlying.tail) else throw new NoSuchElementException("last of empty list")
    }

    def append(node: L): L =
      if (node.nonEmpty) {
        if (nonEmpty) {
          last.tail = node
          underlying
        } else node
      } else underlying

    def reverse: L = {
      @tailrec def rec(last: L, current: L): L =
        if (current ne null) {
          val next = current.tail
          current.tail = last
          rec(current, next)
        } else last
      rec(null, underlying)
    }

    def flatMap[P >: Null <: ImsiList[P]](f: L ⇒ P): P = {
      @tailrec def rec(current: L, result: P, resultLast: P): P =
        if (current ne null) {
          val next = f(current)
          if (result ne null) {
            resultLast.tail = next
            rec(current.tail, result, resultLast.last)
          } else rec(current.tail, next, next)
        } else result
      rec(underlying, null, null)
    }

    def foreach(f: L ⇒ Unit): Unit = {
      @tailrec def rec(current: L): Unit =
        if (current ne null) {
          f(current)
          rec(current.tail)
        }
      rec(underlying)
    }

    def foldLeft[T](zero: T)(f: (T, L) ⇒ T): T = {
      @tailrec def rec(current: L, acc: T): T =
        if (current ne null) rec(current.tail, f(acc, current)) else acc
      rec(underlying, zero)
    }

    /**
     * Partitions the list into two disjunct lists.
     * The first one contains all nodes that DO satisfy the given predicate
     * and the second one all nodes that DO NOT satisfy the predicate.
     */
    def partition(f: L ⇒ Boolean): (L, L) = {
      @tailrec def rec(current: L, a: L, lastA: L, b: L, lastB: L): (L, L) =
        if (current ne null) {
          val next = current.tail
          current.tail = null
          if (f(current)) {
            if (lastA ne null) {
              lastA.tail = current
              rec(next, a, current, b, lastB)
            } else rec(next, current, current, b, lastB)
          } else {
            if (lastB ne null) {
              lastB.tail = current
              rec(next, a, lastA, b, current)
            } else rec(next, a, lastA, current, current)
          }
        } else (a, b)
      rec(underlying, null, null, null, null)
    }

    /**
     * Splits this list after `count` elements and returns the head of the trailing segment.
     * The underlying segment then forms a list holding `count` elements.
     * Throws an `IllegalArgumentException` if `count == 0 || count > size`.
     * (`underlying.isEmpty` requires special treatment in any case!)
     */
    def drop(count: Int): L = {
      @tailrec def rec(remaining: Int, current: L, last: L): L =
        if (remaining == 0) {
          requireArg(last ne null)
          last.tail = null
          current
        } else {
          requireArg(current ne null)
          rec(remaining - 1, current.tail, current)
        }
      rec(count, underlying, null)
    }

    def toList: List[L] = {
      val buf = ListBuffer.empty[L]
      foreach { node ⇒ buf += node; () }
      buf.toList
    }
  }
}