/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.io

import swave.core._

package object files {

  implicit class RichSpout(val underlying: Spout.type) extends SpoutFromFiles
  implicit class RichDrain(val underlying: Drain.type) extends DrainToFiles

}
