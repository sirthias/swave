/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.util

import com.typesafe.config.Config
import swave.core.ConfigurationException

private[swave] abstract class SettingsCompanion[T](prefix: String) {
  import com.typesafe.config.ConfigFactory._

  def apply(configOverrides: String, classLoader: ClassLoader = getClass.getClassLoader): T =
    apply(parseString(configOverrides).withFallback(defaultReference(classLoader)))

  def apply(config: Config): T =
    try fromSubConfig(config getConfig prefix)
    catch { case e: IllegalArgumentException ⇒ configError(e.getMessage) }

  def fromSubConfig(c: Config): T

  def configError(msg: String): Nothing = throw new ConfigurationException(msg)
}
