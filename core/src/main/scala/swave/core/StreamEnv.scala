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

import scala.concurrent.{ ExecutionContext, Future }
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.Logger
import swave.core.impl.StreamEnvImpl
import swave.core.util._

abstract class StreamEnv private[swave] {

  def name: String

  def config: Config

  def settings: StreamEnv.Settings

  def classLoader: ClassLoader

  def startTime: Long

  def log: Logger

  def scheduler: Scheduler

  def dispatchers: DispatcherSetup

  implicit def defaultDispatcher: ExecutionContext

  def shutdown(): Future[Unit]
}

object StreamEnv {

  case class Settings(
      throughput: Int,
      maxBatchSize: Int,
      dispatcherSettings: DispatcherSetup.Settings,
      schedulerSettings: Scheduler.Settings) {

    require(throughput > 0)
    require(0 < maxBatchSize && maxBatchSize <= 1024 * 1024)
  }
  object Settings extends SettingsCompanion[Settings]("swave.core") {
    def fromSubConfig(c: Config): Settings =
      Settings(
        throughput = c getInt "throughput",
        maxBatchSize = c getInt "max-batch-size",
        dispatcherSettings = DispatcherSetup.Settings fromSubConfig c.getConfig("dispatcher"),
        schedulerSettings = Scheduler.Settings fromSubConfig c.getConfig("scheduler"))
  }

  def apply(
    name: String = "default",
    config: Option[Config] = None,
    settings: Option[Settings] = None,
    classLoader: Option[ClassLoader] = None): StreamEnv = {
    val cl = classLoader getOrElse getClass.getClassLoader
    val conf = config getOrElse ConfigFactory.load(cl)
    val sets = settings getOrElse Settings(conf)
    new StreamEnvImpl(name, conf, sets, cl)
  }
}
