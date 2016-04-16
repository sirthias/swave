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
import scala.concurrent.duration.{ Duration, FiniteDuration }
import com.typesafe.config.Config
import swave.core.impl.SchedulerImpl
import swave.core.util.SettingsCompanion

abstract class Scheduler private[swave] {

  final def schedule(interval: FiniteDuration)(body: ⇒ Unit)(implicit ec: ExecutionContext): Cancellable =
    schedule(Duration.Zero, interval)(body)

  final def schedule(initialDelay: FiniteDuration, interval: FiniteDuration)(body: ⇒ Unit)(implicit ec: ExecutionContext): Cancellable =
    schedule(initialDelay, interval, Runnable(body))

  final def schedule(interval: FiniteDuration, body: Runnable)(implicit ec: ExecutionContext): Cancellable =
    schedule(Duration.Zero, interval, body)

  def schedule(initialDelay: FiniteDuration, interval: FiniteDuration, body: Runnable)(implicit ec: ExecutionContext): Cancellable

  final def scheduleOnce(delay: FiniteDuration)(body: ⇒ Unit)(implicit ec: ExecutionContext): Cancellable =
    scheduleOnce(delay, Runnable(body))

  def scheduleOnce(delay: FiniteDuration, body: Runnable)(implicit ec: ExecutionContext): Cancellable
}

object Scheduler {

  case class Settings() // TODO: model Scheduler configuration

  object Settings extends SettingsCompanion[Settings]("swave.core.scheduler") {
    def fromSubConfig(c: Config): Settings = Settings()
  }

  def apply(settings: Settings): Scheduler = new SchedulerImpl(settings)
}
