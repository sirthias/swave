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
import scala.concurrent.duration.Duration
import com.typesafe.config.Config
import swave.core.impl.DispatcherImpl
import swave.core.util._

abstract class Dispatcher private[swave] {

  def name: String

  def executionContext: ExecutionContext

}

object Dispatcher {

  case class Settings(name: String, threadPoolConfig: ThreadPoolConfig)
  object Settings {
    def apply(name: String, config: Config, defaultThreadPoolConfig: Config): Settings =
      Settings(name = name, threadPoolConfig = ThreadPoolConfig(config, defaultThreadPoolConfig))
  }

  sealed trait ThreadPoolConfig
  object ThreadPoolConfig {
    def apply(c: Config, defaultThreadPoolConfig: Config): ThreadPoolConfig =
      c.getOptionalConfig("fork-join") → c.getOptionalConfig("thread-pool") match {
        case (Some(x), None)    ⇒ ForkJoin(x withFallback defaultThreadPoolConfig.getConfig("fork-join"))
        case (None, Some(x))    ⇒ ThreadPool(x withFallback defaultThreadPoolConfig.getConfig("thread-pool"))
        case (None, None)       ⇒ sys.error(s"Config section '$c' is missing a mandatory 'fork-join' or 'thread-pool' section!")
        case (Some(_), Some(_)) ⇒ sys.error(s"Config section '$c' must only define either a 'fork-join' or a 'thread-pool' section, not both!")
      }

    case class Size(factor: Double, min: Int, max: Int) {
      require(factor >= 0.0)
      require(min >= 0)
      require(max >= min)
    }
    object Size {
      def apply(c: Config): Size =
        Size(c getDouble "factor", c getInt "min", c getInt "max")
    }

    case class ForkJoin(parallelism: Size) extends ThreadPoolConfig
    object ForkJoin {
      def apply(c: Config): ForkJoin = ForkJoin(Size(c.getConfig("parallelism")))
    }

    case class ThreadPool(
        poolSize: Size,
        idleTimeout: Duration,
        prestart: ThreadPool.Prestart) extends ThreadPoolConfig {
      require(idleTimeout > Duration.Zero)
    }
    object ThreadPool {
      sealed trait Prestart
      object Prestart {
        case object Off extends Prestart
        case object First extends Prestart
        case object All extends Prestart
      }

      def apply(c: Config): ThreadPool =
        ThreadPool(
          poolSize = Size(c.getConfig("parallelism")),
          idleTimeout = c getScalaDuration "idle-timeout",
          prestart = c.getString("prestart").toLowerCase match {
            case "off"   ⇒ Prestart.Off
            case "first" ⇒ Prestart.First
            case "All"   ⇒ Prestart.All
          })
    }
  }

  def apply(settings: Settings): Dispatcher = new DispatcherImpl(settings)
}
