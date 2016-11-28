/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.util

import scala.annotation.tailrec
import shapeless._
import swave.core._
import swave.core.impl.Inport

private[swave] abstract class AbstractInportList[L >: Null <: AbstractInportList[L]](final val in: Inport, tail: L)
    extends ImsiList[L](tail)

private[swave] object AbstractInportList {
  implicit class InportListOps[L >: Null <: AbstractInportList[L]](private val underlying: L) extends AnyVal {

    def contains(in: Inport): Boolean = {
      @tailrec def rec(current: L): Boolean = (current ne null) && ((current.in eq in) || rec(current.tail))
      rec(underlying)
    }

    def find_!(in: Inport): L = {
      @tailrec def rec(current: L): L =
        if (current ne null) {
          if (current.in eq in) current
          else rec(current.tail)
        } else throw new IllegalStateException(s"Element for InPort `$in` was required but not found in " + underlying)
      rec(underlying)
    }

    def at(ix: Int): Inport = {
      @tailrec def rec(current: L, cnt: Int): Inport =
        if (current ne null) {
          if (cnt > 0) rec(current.tail, cnt - 1) else current.in
        } else throw new NoSuchElementException(s"Index $ix of list with size ${underlying.size}")
      rec(underlying, ix)
    }

    def indexOf(in: Inport): Int = {
      @tailrec def rec(current: L, ix: Int): Int =
        if (current ne null) {
          if (current.in eq in) ix
          else rec(current.tail, ix + 1)
        } else -1
      rec(underlying, 0)
    }

    def remove(in: Inport): L = {
      @tailrec def rec(last: L, current: L): L =
        if (current ne null) {
          if (current.in eq in) {
            if (last ne null) {
              last.tail = current.tail
              current.tail = current // signal that the node has been removed
              underlying
            } else current.tail
          } else rec(current, current.tail)
        } else underlying
      rec(null, underlying)
    }

    def remove_!(in: Inport): L = {
      @tailrec def rec(last: L, current: L): L =
        if (current ne null) {
          if (current.in eq in) {
            if (last ne null) {
              last.tail = current.tail
              underlying
            } else current.tail
          } else rec(current, current.tail)
        } else throw new IllegalStateException(s"Element for InPort `$in` was required but not found in " + underlying)
      rec(null, underlying)
    }

    def toReversedSpoutHList: HList = {
      @tailrec def rec(current: L, result: HList): HList =
        if (current ne null) {
          rec(current.tail, shapeless.::(current.in, result))
        } else result
      rec(underlying, HNil)
    }

    def toStageList: List[Stage] = {
      val tail = if (underlying.tail ne null) underlying.tail.toStageList else Nil
      underlying.in.stageImpl :: tail
    }
  }
}

private[swave] final class InportList(in: Inport, tail: InportList) extends AbstractInportList[InportList](in, tail)

private[swave] object InportList {
  def empty: InportList                           = null
  def apply(in: Inport, tail: InportList = empty) = new InportList(in, tail)

  def fill(n: Int, in: ⇒ Inport): InportList = {
    @tailrec def rec(remaining: Int, result: InportList): InportList =
      if (remaining > 0) rec(remaining - 1, in +: result) else result
    rec(n, empty)
  }

  def fromHList(inports: HList): InportList = {
    @tailrec def rec(remaining: HList, result: InportList): InportList =
      remaining match {
        case (head: Spout[_]) :: tail ⇒ rec(tail, head.inport +: result)
        case (head: Inport) :: tail   ⇒ rec(tail, head +: result)
        case HNil                     ⇒ result.reverse
        case _                        ⇒ throw new IllegalStateException("Unexpected HList content")
      }
    rec(inports, empty)
  }

  implicit class InportListOps(private val underlying: InportList) extends AnyVal {
    def :+(in: Inport): InportList = underlying append InportList(in)
    def +:(in: Inport): InportList = new InportList(in, underlying)
    def :++[S, O](subs: TraversableOnce[S])(implicit ev: Streamable.Aux[S, O]): InportList =
      subs.foldLeft(underlying)((ins, sub) ⇒ ins :+ ev(sub).inport)
  }
}

private[swave] final class InportAnyRefList(in: Inport, var value: AnyRef, tail: InportAnyRefList)
    extends AbstractInportList[InportAnyRefList](in, tail)

private[swave] object InportAnyRefList {
  def empty: InportAnyRefList                           = null
  def apply(in: Inport, tail: InportAnyRefList = empty) = new InportAnyRefList(in, null, tail)

  implicit class InportAnyRefListOps(private val underlying: InportAnyRefList) extends AnyVal {
    def +:(in: Inport): InportAnyRefList = InportAnyRefList(in, underlying)
    def :+(in: Inport): InportAnyRefList = underlying append InportAnyRefList(in)
  }
}
