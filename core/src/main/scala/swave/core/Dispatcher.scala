/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.{Duration, FiniteDuration}
import com.typesafe.config.Config
import swave.core.impl.DispatcherImpl
import swave.core.util._
import swave.core.macros._

abstract class Dispatcher private[core] extends ExecutionContextExecutor {

  def name: String

  def settings: Dispatcher.Settings
}

object Dispatcher {

  final case class Settings(name: String, threadPoolConfig: ThreadPoolConfig) {
    requireArg(name.nonEmpty)
  }
  object Settings {
    def apply(name: String, config: Config, defaultThreadPoolConfig: Config): Settings =
      Settings(name = name, threadPoolConfig = ThreadPoolConfig(config, defaultThreadPoolConfig))
  }

  sealed abstract class ThreadPoolConfig
  object ThreadPoolConfig {
    def apply(c: Config, defaultThreadPoolConfig: Config): ThreadPoolConfig = {
      def poolConf(x: Config, name: String) = x.withFallback(defaultThreadPoolConfig getConfig name)
      def forkJoin(x: Config)               = ForkJoin(poolConf(x, "fork-join"))
      def threadPool(x: Config)             = ThreadPool(poolConf(x, "thread-pool"))
      c.getOptionalConfig("fork-join") → c.getOptionalConfig("thread-pool") match {
        case (Some(x), None) ⇒ forkJoin(x)
        case (None, Some(x)) ⇒ threadPool(x)
        case (None, None) ⇒
          sys.error(s"Config section '$c' is missing a mandatory 'fork-join' or 'thread-pool' section!")
        case (Some(x), Some(y)) ⇒
          if (c.hasPath("type")) {
            c.getString("type") match {
              case "fork-join"   ⇒ forkJoin(x)
              case "thread-pool" ⇒ threadPool(y)
            }
          } else
            sys.error(
              s"Config section '$c' must only define either a 'fork-join' or a 'thread-pool' section, not both. " +
                "Either remove one of them or add a `type = fork-join` or `type = thread-pool` setting to break the ambiguity!")
      }
    }

    final case class Size(factor: Double, min: Int, max: Int) {
      requireArg(factor >= 0.0)
      requireArg(min >= 0)
      requireArg(max >= min)
    }
    object Size {
      def apply(c: Config): Size = Size(c getDouble "factor", c getInt "min", c getInt "max")
    }

    final case class ForkJoin(parallelism: Size, asyncMode: Boolean, daemonic: Boolean) extends ThreadPoolConfig
    object ForkJoin {
      def apply(c: Config): ForkJoin =
        ForkJoin(parallelism = Size(c getConfig "parallelism"), asyncMode = c getString "task-peeking-mode" match {
          case "FIFO" ⇒ true
          case "LIFO" ⇒ false
          case x      ⇒ throw new IllegalArgumentException(s"Illegal task-peeking-mode `$x`. Must be `FIFO` or `LIFO`.")
        }, daemonic = c getBoolean "daemonic")
    }

    final case class ThreadPool(corePoolSize: Size,
                                maxPoolSize: Size,
                                keepAliveTime: FiniteDuration,
                                allowCoreTimeout: Boolean,
                                prestart: ThreadPool.Prestart,
                                daemonic: Boolean)
        extends ThreadPoolConfig {
      requireArg(keepAliveTime > Duration.Zero)
    }
    object ThreadPool {
      sealed abstract class Prestart
      object Prestart {
        case object Off   extends Prestart
        case object First extends Prestart
        case object All   extends Prestart
      }

      def apply(c: Config): ThreadPool =
        ThreadPool(
          corePoolSize = size(c, "core-pool-size"),
          maxPoolSize = size(c, "max-pool-size"),
          keepAliveTime = c getFiniteDuration "keep-alive-time",
          allowCoreTimeout = c getBoolean "allow-core-timeout",
          prestart = c.getString("prestart").toLowerCase match {
            case "off"   ⇒ Prestart.Off
            case "first" ⇒ Prestart.First
            case "All"   ⇒ Prestart.All
          },
          daemonic = c getBoolean "daemonic")

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
