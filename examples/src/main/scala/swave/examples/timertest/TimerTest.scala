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

package swave.examples.timertest

import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import swave.core.util._
import swave.core._

object TimerTest extends App {

  implicit val env = StreamEnv(config = Some(ConfigFactory parseString "swave.core.scheduler.tick-duration = 100ms"))
  import env.defaultDispatcher

  val job = env.scheduler.schedule(1.seconds)(println("COOL!"))

  System.console().readLine()
  println("cancelling")
  requireState(job.cancel())

  System.console().readLine()
  println("shutting down")
  env.shutdown().awaitTermination(2.seconds)

  println("done")
}
