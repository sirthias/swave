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

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.{ FiniteDuration, Duration }
import com.typesafe.config.Config
import swave.core.impl.DispatcherImpl
import swave.core.util._

abstract class Dispatcher private[core] extends ExecutionContextExecutor {

  def name: String

  def settings: Dispatcher.Settings
}

object Dispatcher {

  final case class Settings(name: String, threadPoolConfig: ThreadPoolConfig) {
    require(name.nonEmpty)
  }
  object Settings {
    def apply(name: String, config: Config, defaultThreadPoolConfig: Config): Settings =
      Settings(name = name, threadPoolConfig = ThreadPoolConfig(config, defaultThreadPoolConfig))
  }

  sealed abstract class ThreadPoolConfig
  object ThreadPoolConfig {
    def apply(c: Config, defaultThreadPoolConfig: Config): ThreadPoolConfig =
      c.getOptionalConfig("fork-join") → c.getOptionalConfig("thread-pool") match {
        case (Some(x), None)    ⇒ ForkJoin(x withFallback (defaultThreadPoolConfig getConfig "fork-join"))
        case (None, Some(x))    ⇒ ThreadPool(x withFallback (defaultThreadPoolConfig getConfig "thread-pool"))
        case (None, None)       ⇒ sys.error(s"Config section '$c' is missing a mandatory 'fork-join' or 'thread-pool' section!")
        case (Some(_), Some(_)) ⇒ sys.error(s"Config section '$c' must only define either a 'fork-join' or a 'thread-pool' section, not both!")
      }

    final case class Size(factor: Double, min: Int, max: Int) {
      require(factor >= 0.0)
      require(min >= 0)
      require(max >= min)
    }
    object Size {
      def apply(c: Config): Size = Size(c getDouble "factor", c getInt "min", c getInt "max")
    }

    final case class ForkJoin(parallelism: Size, asyncMode: Boolean) extends ThreadPoolConfig
    object ForkJoin {
      def apply(c: Config): ForkJoin = ForkJoin(
        Size(c getConfig "parallelism"),
        c getString "task-peeking-mode" match {
          case "FIFO" ⇒ true
          case "LIFO" ⇒ false
          case x      ⇒ throw new IllegalArgumentException(s"Illegal task-peeking-mode `$x`. Must be `FIFO` or `LIFO`.")
        })
    }

    final case class ThreadPool(
        corePoolSize: Size,
        maxPoolSize: Size,
        keepAliveTime: FiniteDuration,
        allowCoreTimeout: Boolean,
        prestart: ThreadPool.Prestart) extends ThreadPoolConfig {
      require(keepAliveTime > Duration.Zero)
    }
    object ThreadPool {
      sealed abstract class Prestart
      object Prestart {
        case object Off extends Prestart
        case object First extends Prestart
        case object All extends Prestart
      }

      def apply(c: Config): ThreadPool =
        ThreadPool(
          corePoolSize = size(c, "core-pool-size"),
          maxPoolSize = size(c, "max-pool-size"),
          keepAliveTime = c getFiniteDuration "idle-timeout",
          allowCoreTimeout = c getBoolean "allow-core-timeout",
          prestart = c.getString("prestart").toLowerCase match {
            case "off"   ⇒ Prestart.Off
            case "first" ⇒ Prestart.First
            case "All"   ⇒ Prestart.All
          })

      private def size(c: Config, section: String): Size =
        c getString "fixed-pool-size" match {
          case "off" ⇒ Size(c getConfig section)
          case _ ⇒
            val n = c getInt "fixed-pool-size"
            Size(1.0, n, n)
        }
    }
  }

  def apply(settings: Settings): Dispatcher = DispatcherImpl(settings)
}
