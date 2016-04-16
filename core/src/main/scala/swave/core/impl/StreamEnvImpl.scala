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

import scala.concurrent.{ Future, ExecutionContext }
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import swave.core._

private[core] class StreamEnvImpl(
    val name: String,
    val config: Config,
    val settings: StreamEnv.Settings,
    val classLoader: ClassLoader) extends StreamEnv {

  val startTime = System.currentTimeMillis()

  val log = Logger(LoggerFactory.getLogger(name))

  val dispatchers = new DispatcherSetupImpl(settings.dispatcherSettings)

  val scheduler = new SchedulerImpl(settings.schedulerSettings)

  implicit val defaultDispatcher: ExecutionContext = dispatchers.defaultDispatcher

  def shutdown(): Future[Unit] = {
    import swave.core.util._
    FastFuture.sequence(scheduler.shutdown() :: dispatchers.shutdownAll() :: Nil).fast.map(_ ⇒ ())
  }

}
