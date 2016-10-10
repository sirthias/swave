/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

sealed abstract class Split

object Split {
  sealed abstract class Command
  case object Drop   extends Command // drop the current element
  case object Append extends Command // append to current sub-stream, if no sub-stream is currently open start a new one
  case object Last   extends Command // append element (same as `Append`) and complete the current sub-stream afterwards
  case object First  extends Command // complete the current sub-stream (if there is one) and start a new one with the current element
}
