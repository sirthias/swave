/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.util

import scala.concurrent.duration._

final class RichDuration(val underlying: Duration) extends AnyVal {

  def orElse(that: Duration): Duration =
    if (underlying eq Duration.Undefined) that else underlying
}
