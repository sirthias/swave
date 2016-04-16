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
import scala.collection.LinearSeq
import scala.collection.generic.CanBuildFrom

final class RichSeq[A](val underlying: Seq[A]) extends AnyVal {

  def mapFind[B](f: A ⇒ Option[B]): Option[B] = {
    @tailrec def linearRec(seq: LinearSeq[A]): Option[B] =
      if (seq.nonEmpty) {
        val x = f(seq.head)
        if (x.isEmpty) linearRec(seq.tail) else x
      } else None

    @tailrec def indexedRec(ix: Int): Option[B] =
      if (ix < underlying.length) {
        val x = f(underlying(ix))
        if (x.isEmpty) indexedRec(ix + 1) else x
      } else None

    underlying match {
      case x: LinearSeq[A] ⇒ linearRec(x)
      case _               ⇒ indexedRec(0)
    }
  }

  def foreachWithIndex(f: (A, Int) ⇒ Unit): Unit = {
    requireState(underlying.isInstanceOf[IndexedSeq[A]])
    @tailrec def rec(ix: Int): Unit =
      if (ix < underlying.size) {
        f(underlying(ix), ix)
        rec(ix + 1)
      }
    rec(0)
  }

  def mapWithIndex[B, That](f: (A, Int) ⇒ B)(implicit bf: CanBuildFrom[Seq[A], B, That]): That = {
    requireState(underlying.isInstanceOf[IndexedSeq[A]])
    val b = bf(underlying)
    b.sizeHint(underlying)
    @tailrec def rec(ix: Int): Unit =
      if (ix < underlying.size) {
        b += f(underlying(ix), ix)
        rec(ix + 1)
      }
    rec(0)
    b.result()
  }
}
