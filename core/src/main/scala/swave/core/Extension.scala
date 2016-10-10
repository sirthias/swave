/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import com.typesafe.config.Config
import scala.concurrent.duration.FiniteDuration
import swave.core.util._

trait Extension

trait ExtensionId[T <: Extension] {

  /**
    * Returns an instance of the extension identified by this ExtensionId instance.
    */
  def apply(env: StreamEnv): T =
    env.getOrLoadExtension(this).await(env.settings.extensionSettings.constructionTimeout)

  def createExtension(env: StreamEnv): T

  // ExtensionId instances are used as keys in a ConcurrentHashMap,
  // therefore we pin down reference equality semantics for them here
  override final def hashCode: Int               = System.identityHashCode(this)
  override final def equals(other: Any): Boolean = this eq other.asInstanceOf[AnyRef]
}

object Extension {

  final case class Settings(constructionTimeout: FiniteDuration)

  object Settings extends SettingsCompanion[Settings]("swave.core.extensions") {
    def fromSubConfig(c: Config): Settings =
      Settings(constructionTimeout = c getFiniteDuration "construction-timeout")
  }

}
