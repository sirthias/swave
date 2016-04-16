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

import scala.concurrent.ExecutionContext
import com.typesafe.config.Config
import swave.core.impl.DispatcherSetupImpl
import swave.core.util.SettingsCompanion
import scala.collection.JavaConverters._

abstract class DispatcherSetup private[swave] {

  def defaultDispatcher: ExecutionContext

  def apply(id: Symbol): ExecutionContext
}

object DispatcherSetup {

  case class Settings(defined: Map[Symbol, Dispatcher.Settings])

  object Settings extends SettingsCompanion[Settings]("swave.core.dispatcher") {
    def fromSubConfig(c: Config): Settings = {
      val defConf = c getConfig "default-config"
      val definition = c getConfig "definition"
      val defined = definition.root().keySet().asScala.map(n ⇒ Dispatcher.Settings(n, definition getConfig n, defConf))
      Settings(defined.map(s ⇒ Symbol(s.name) → s).toMap)
    }
  }

  def apply(settings: Settings): DispatcherSetup = new DispatcherSetupImpl(settings)
}
