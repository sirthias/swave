/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import swave.core.impl.stages.inout.CouplingStage
import swave.core.util.AnyRefExtractor.Extraction

/**
  * A simple streaming component with one input, one output port and no internal transformation logic.
  *
  * It is typically used to manually connect two ports of a stream graph that cannot
  * be otherwise connected via the streaming DSL, e.g. for creating cycles.
  */
final class Coupling[T] private {
  private[this] val stage = new CouplingStage

  val in: Drain[T, Unit] = Drain(stage)
  val out: Spout[T]      = new Spout(stage)
}

object Coupling {
  def apply[T]: Coupling[T] = new Coupling[T]

  def unapply[T](value: Coupling[T]): Extraction[(Drain[T, Unit], Spout[T])] =
    new Extraction(value.in -> value.out)
}
