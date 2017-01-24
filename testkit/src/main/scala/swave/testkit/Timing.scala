/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.testkit

import com.typesafe.config.Config
import swave.core.impl.util.SettingsCompanion

import scala.collection._
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import swave.core.macros._
import swave.core.util._

object Timing {

  final case class Settings(factor: Double, scalingChain: immutable.Seq[Double], singleExpectDefault: FiniteDuration) {

    requireArg(factor > 0.0 && !factor.isInfinite, "`click` must be finite and > 0")
    requireArg(
      scalingChain.nonEmpty && scalingChain.size <= 16,
      "`scalingChain` must be non-empty and have at most 16 elements")
    requireArg(singleExpectDefault > Duration.Zero, "`singleExpectDefault` must be > 0")
  }

  object Settings extends SettingsCompanion[Settings]("swave.test.timing") {
    def fromSubConfig(c: Config): Settings =
      Settings(
        factor = c getDouble "factor",
        scalingChain = c.getDoubleList("scaling-chain").asScala.map(_.doubleValue)(breakOut),
        singleExpectDefault = c getFiniteDuration "single-expect-default"
      )
  }
}
