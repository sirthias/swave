/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

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
