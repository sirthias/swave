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

import java.util.concurrent.TimeoutException
import scala.annotation.tailrec
import scala.concurrent.duration._
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import swave.core._

private[core] final class StreamEnvImpl(
    val name: String,
    val config: Config,
    val settings: StreamEnv.Settings,
    val classLoader: ClassLoader) extends StreamEnv {

  val startTime = System.currentTimeMillis()

  val log = Logger(LoggerFactory.getLogger(name))

  val dispatchers = DispatchersImpl(settings.dispatcherSettings)

  val scheduler = SchedulerImpl(settings.schedulerSettings)

  if (settings.logConfigOnStart) log.info(settings.toString) // TODO: improve rendering

  def defaultDispatcher = dispatchers.defaultDispatcher

  def shutdown(): StreamEnv.Termination =
    new StreamEnv.Termination {
      val schedulerTermination = scheduler.shutdown()
      val dispatchersTermination = dispatchers.shutdownAll()

      def isTerminated: Boolean = schedulerTermination.isCompleted && unterminatedDispatchers.isEmpty

      def unterminatedDispatchers: List[String] = dispatchersTermination()

      def awaitTermination(timeout: FiniteDuration): Unit = {
        require(timeout >= Duration.Zero)
        var deadline = System.nanoTime() + timeout.toNanos
        if (deadline < 0) deadline = Long.MaxValue // overflow protection

        @tailrec def await(): Unit =
          if (!isTerminated) {
            if (System.nanoTime() < deadline) {
              Thread.sleep(1L)
              await()
            } else {
              val unterminated =
                if (schedulerTermination.isCompleted) unterminatedDispatchers
                else "scheduler" :: unterminatedDispatchers
              throw new TimeoutException(s"StreamEnv did not shut down within specified timeout of $timeout.\n" +
                s"Unterminated dispatchers: [${unterminated.mkString(", ")}]")
            }
          }

        await()
      }
    }

}
