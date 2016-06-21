/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.examples.timertest

import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import swave.core._

object TimerTest extends App {

  implicit val env = StreamEnv(config = Some(ConfigFactory parseString "swave.core.scheduler.tick-duration = 100ms"))
  import env.defaultDispatcher

  val job = env.scheduler.schedule(1.seconds)(println("COOL!"))

  System.console().readLine()
  println("cancelling")
  require(job.cancel())

  System.console().readLine()
  println("shutting down")
  env.shutdown().awaitTermination(2.seconds)

  println("done")
}
