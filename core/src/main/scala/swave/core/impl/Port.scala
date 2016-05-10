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

package swave.core.impl

import swave.core.PipeElem

private[swave] sealed trait Port {
  def pipeElem: PipeElem.Basic

  def xSeal(ctx: RunContext): Unit
}

private[swave] sealed trait Inport extends Port {

  def subscribe()(implicit from: Outport): Unit

  def request(n: Long)(implicit from: Outport): Unit

  def cancel()(implicit from: Outport): Unit
}

private[swave] sealed trait Outport extends Port {

  def onSubscribe()(implicit from: Inport): Unit

  def onNext(elem: AnyRef)(implicit from: Inport): Unit

  def onComplete()(implicit from: Inport): Unit

  def onError(error: Throwable)(implicit from: Inport): Unit
}

private[swave] abstract class PipeElemImpl extends Inport with Outport { this: PipeElem.Basic ⇒
  private[this] var _moduleStarts = List.empty[PipeElem.Module]
  private[this] var _moduleEnds = List.empty[PipeElem.Module]

  def pipeElem = this

  def moduleEntries = _moduleStarts
  def moduleExits = _moduleEnds

  def markModuleEntry(module: PipeElem.Module): Unit = _moduleStarts ::= module
  def markModuleExit(module: PipeElem.Module): Unit = _moduleEnds ::= module
}