/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import com.typesafe.config.{Config, ConfigFactory}
import scala.concurrent.Future
import scala.concurrent.duration._
import com.typesafe.scalalogging.Logger
import swave.core.io.files.FileIO
import swave.core.impl.StreamEnvImpl
import swave.core.impl.util.SettingsCompanion
import swave.core.util._
import swave.core.macros._

abstract class StreamEnv private[core] {

  def name: String

  def config: Config

  def settings: StreamEnv.Settings

  def classLoader: ClassLoader

  def startTime: Long

  def log: Logger

  def scheduler: Scheduler

  def dispatchers: Dispatchers

  implicit def defaultDispatcher: Dispatcher

  def shutdown(): StreamEnv.Termination

  def getOrLoadExtension[T <: Extension](ext: ExtensionId[T]): Future[T]
}

object StreamEnv {

  final case class Settings(throughput: Int,
                            maxBatchSize: Int,
                            logConfigOnStart: Boolean,
                            subscriptionTimeout: Duration,
                            dispatcherSettings: Dispatchers.Settings,
                            schedulerSettings: Scheduler.Settings,
                            fileIOSettings: FileIO.Settings,
                            extensionSettings: Extension.Settings) {

    requireArg(throughput > 0, "`throughput` must be > 0")
    requireArg(0 < maxBatchSize && maxBatchSize <= 1024 * 1024, "`maxBatchSize` must be > 0 and <= 1M")

    def withThroughput(throughput: Int)                        = copy(throughput = throughput)
    def withMaxBatchSize(maxBatchSize: Int)                    = copy(maxBatchSize = maxBatchSize)
    def withLogConfigOnStart(logConfigOnStart: Boolean = true) = copy(logConfigOnStart = logConfigOnStart)
    def withSubscriptionTimeout(subscriptionTimeout: Duration) = copy(subscriptionTimeout = subscriptionTimeout)

    def withDispatcherSettingsMapped(f: Dispatchers.Settings => Dispatchers.Settings) =
      copy(dispatcherSettings = f(dispatcherSettings))
    def withSchedulerSettingsMapped(f: Scheduler.Settings => Scheduler.Settings) =
      copy(schedulerSettings = f(schedulerSettings))
    def withFileIOSettingsMapped(f: FileIO.Settings => FileIO.Settings) =
      copy(fileIOSettings = f(fileIOSettings))
    def withExtensionSettingsMapped(f: Extension.Settings => Extension.Settings) =
      copy(extensionSettings = f(extensionSettings))
  }
  object Settings extends SettingsCompanion[Settings]("swave.core") {
    def fromSubConfig(c: Config): Settings =
      Settings(
        throughput = c getInt "throughput",
        maxBatchSize = c getInt "max-batch-size",
        logConfigOnStart = c getBoolean "log-config-on-start",
        subscriptionTimeout = c getScalaDuration "subscription-timeout",
        dispatcherSettings = Dispatchers.Settings fromSubConfig c.getConfig("dispatcher"),
        schedulerSettings = Scheduler.Settings fromSubConfig c.getConfig("scheduler"),
        fileIOSettings = FileIO.Settings fromSubConfig c.getConfig("file-io"),
        extensionSettings = Extension.Settings fromSubConfig c.getConfig("extensions"))
  }

  def apply(name: String = "default",
            config: Option[Config] = None,
            settings: Option[Settings] = None,
            mapSettings: Settings => Settings = identityFunc,
            classLoader: Option[ClassLoader] = None): StreamEnv = {
    val cl   = classLoader getOrElse getClass.getClassLoader
    val conf = config getOrElse ConfigFactory.empty withFallback ConfigFactory.load(cl)
    val sets = mapSettings(settings getOrElse Settings(conf))
    new StreamEnvImpl(name, conf, sets, cl)
  }

  abstract class Termination private[core] {
    def isTerminated: Boolean

    def unterminatedDispatchers: List[String]

    def awaitTermination(timeout: FiniteDuration): Unit
  }
}
