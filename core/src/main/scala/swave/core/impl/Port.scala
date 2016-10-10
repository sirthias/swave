/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import swave.core.{Module, PipeElem}

private[swave] sealed trait Port {
  def pipeElem: PipeElem

  def xSeal(ctx: RunContext): Unit

  def isSealed: Boolean
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

private[swave] abstract class PipeElemImpl extends Inport with Outport { this: PipeElem ⇒
  private[this] var _boundaryOf = List.empty[Module.ID]

  final def pipeElem = this

  final def boundaryOf = _boundaryOf

  final def markAsBoundaryOf(moduleID: Module.ID): Unit = _boundaryOf ::= moduleID
}
