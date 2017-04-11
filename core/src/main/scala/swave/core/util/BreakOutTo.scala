/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.util

import scala.collection.generic.CanBuildFrom

/**
  * Enables fluent (inline) type breakout in chains of collection transformations, e.g.
  * {{{
  *   scala> Vector(1, 2, 3).map(_ + 10)(BreakOutTo[List].here).reverse
  *   res0: List[Int] = List(13, 12, 11)
  * }}}
  */
final class BreakOutTo[M[_]] private {
  def here[From, T](implicit b: CanBuildFrom[Nothing, T, M[T]]): CanBuildFrom[From, T, M[T]] = collection.breakOut
}

object BreakOutTo {
  private val instance = new BreakOutTo[shapeless.Id]

  def apply[M[_]]: BreakOutTo[M] = instance.asInstanceOf[BreakOutTo[M]]
}
