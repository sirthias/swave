/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave

import scala.concurrent.duration._
import swave.testkit.impl.TestkitExtension
import swave.core.StreamEnv
import swave.core.util._

package object testkit {

  implicit class TestDuration(val duration: FiniteDuration) extends AnyVal {
    def dilated(implicit env: StreamEnv): FiniteDuration =
      duration timesFiniteFactor TestkitExtension(env).settings.timingDefaults.factor
  }

}
