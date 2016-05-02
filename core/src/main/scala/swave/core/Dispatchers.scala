/*
 * Copyright © 2016 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swave.core

import com.typesafe.config.Config
import scala.collection.JavaConverters._
import swave.core.util.SettingsCompanion

abstract class Dispatchers private[core] {

  def settings: Dispatchers.Settings

  def defaultDispatcher: Dispatcher

  def apply(id: Symbol): Dispatcher
}

object Dispatchers {

  final case class Settings(dispatcherDefs: Map[Symbol, Dispatcher.Settings])

  object Settings extends SettingsCompanion[Settings]("swave.core.dispatcher") {
    def fromSubConfig(c: Config): Settings = {
      val defConf = c getConfig "default-config"
      val definition = c getConfig "definition"
      Settings {
        definition.root().keySet().iterator().asScala.foldLeft(Map.empty[Symbol, Dispatcher.Settings]) { (map, name) ⇒
          map.updated(Symbol(name), Dispatcher.Settings(name, definition getConfig name, defConf))
        }
      }
    }
  }
}
