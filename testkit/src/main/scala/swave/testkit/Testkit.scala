/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.testkit

import com.typesafe.config.Config
import swave.core.impl.util.SettingsCompanion
import swave.core.macros._

object Testkit {

  final case class Settings(timingDefaults: Timing.Settings)

  object Settings extends SettingsCompanion[Settings]("swave.test") {
    def fromSubConfig(c: Config): Settings =
      Settings(timingDefaults = Timing.Settings fromSubConfig c.getConfig("timing"))
  }

  sealed abstract class Signal

  object Signal {
    final case class Request(n: Long)      extends Signal { requireArg(n > 0, s"`n` must be > 0") }
    case object Cancel                     extends Signal
    final case class OnNext(value: Any)    extends Signal
    case object OnComplete                 extends Signal
    final case class OnError(e: Throwable) extends Signal
  }
}
