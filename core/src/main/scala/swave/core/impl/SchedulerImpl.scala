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

package swave.core.impl

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Future, ExecutionContext }
import swave.core._

private[core] final class SchedulerImpl(settings: Scheduler.Settings) extends Scheduler {

  def schedule(initialDelay: FiniteDuration, interval: FiniteDuration,
    body: Runnable)(implicit ec: ExecutionContext): Cancellable = ???

  def scheduleOnce(delay: FiniteDuration, body: Runnable)(implicit ec: ExecutionContext): Cancellable = ???

  def shutdown(): Future[Unit] = Future.successful(())
}
