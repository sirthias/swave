/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}
import com.typesafe.config.Config
import swave.core.macros._
import swave.core.util._

trait Scheduler {

  def settings: Scheduler.Settings

  final def schedule(interval: FiniteDuration)(body: ⇒ Unit)(implicit ec: ExecutionContext): Cancellable =
    schedule(Duration.Zero, interval)(body)

  final def schedule(initialDelay: FiniteDuration, interval: FiniteDuration)(body: ⇒ Unit)(
      implicit ec: ExecutionContext): Cancellable =
    schedule(initialDelay, interval, Runnable(body))

  final def schedule(interval: FiniteDuration, r: Runnable)(implicit ec: ExecutionContext): Cancellable =
    schedule(Duration.Zero, interval, r)

  def schedule(initialDelay: FiniteDuration, interval: FiniteDuration, r: Runnable)(
      implicit ec: ExecutionContext): Cancellable

  final def scheduleOnce(delay: FiniteDuration)(body: ⇒ Unit)(implicit ec: ExecutionContext): Cancellable =
    scheduleOnce(delay, Runnable(body))

  def scheduleOnce(delay: FiniteDuration, r: Runnable)(implicit ec: ExecutionContext): Cancellable
}

object Scheduler {

  final case class Settings(tickDuration: FiniteDuration, ticksPerWheel: Int) {
    requireArg(tickDuration > Duration.Zero)
    requireArg(ticksPerWheel > 0 && isPowerOf2(ticksPerWheel))
  }

  object Settings extends SettingsCompanion[Settings]("swave.core.scheduler") {
    def fromSubConfig(c: Config): Settings =
      Settings(tickDuration = c getFiniteDuration "tick-duration", ticksPerWheel = c getInt "ticks-per-wheel")
  }
}
