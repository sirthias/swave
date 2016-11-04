/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import com.typesafe.config.Config
import scala.collection.JavaConverters._
import swave.core.impl.util.SettingsCompanion

abstract class Dispatchers private[core] {

  def settings: Dispatchers.Settings

  def defaultDispatcher: Dispatcher

  def apply(id: String): Dispatcher
}

object Dispatchers {

  final case class Settings(dispatcherDefs: Map[String, Dispatcher.Settings])

  object Settings extends SettingsCompanion[Settings]("swave.core.dispatcher") {
    def fromSubConfig(c: Config): Settings = {
      val defConf    = c getConfig "default-config"
      val definition = c getConfig "definition"
      Settings {
        definition.root().keySet().iterator().asScala.foldLeft(Map.empty[String, Dispatcher.Settings]) { (map, name) ⇒
          map.updated(name, Dispatcher.Settings(name, definition getConfig name, defConf))
        }
      }
    }
  }
}
