/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl

import swave.core._

private[core] final class ModuleMarker(val name: String) extends PipeElem.Module {

  def markEntry(port: Port): Unit =
    port.asInstanceOf[PipeElemImpl].markModuleEntry(this)

  def markExit(port: Port): Unit =
    port.asInstanceOf[PipeElemImpl].markModuleExit(this)

  override def toString = s"Marker($name)"
}
