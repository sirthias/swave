/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.util

import scala.concurrent.duration._
import swave.core.macros._

final class RichFiniteDuration(val underlying: FiniteDuration) extends AnyVal {

  def timesFiniteFactor(factor: Double): FiniteDuration = {
    requireArg(!factor.isInfinite && !factor.isNaN, "`factor` must not be infinite and not be NaN")
    Duration.fromNanos((underlying.toNanos * factor + 0.5).toLong)
  }
}
