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

package swave.core

import swave.core.impl.stages.inout.CouplingStage

sealed abstract class Coupling[T] {
  def in: Drain[T, Unit]
  def out: Stream[T]
}

object Coupling {

  def apply[T]: Coupling[T] =
    new Coupling[T] {
      private[this] val pipe = new CouplingStage
      val in = new Drain[T, Unit](pipe, ())
      val out = new Stream[T](pipe)
    }
}
