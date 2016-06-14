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

package swave.core.impl.stages

import scala.util.Try
import org.scalatest.FreeSpec
import scala.concurrent.duration._
import swave.testkit.{ TestDrain, TestStream }
import swave.core.{ StreamEnvShutdown, StreamEnv }

final class DropWithinSpec extends FreeSpec with StreamEnvShutdown {

  implicit val env = StreamEnv()
  import env.defaultDispatcher

  "DropWithin" in {
    val stream = TestStream.probe[Symbol]()
    val drain = TestDrain.probe[Symbol]()

    stream.dropWithin(30.millis).drainTo(drain) shouldEqual Try(())

    drain.send.request(5)
    stream.expect.request(5) within 10.millis
    stream.send.onNext('a)
    stream.expect.request(1) within 10.millis
    stream.send.onNext('b)
    stream.expect.request(1) within 10.millis
    stream.send.onNext('c)
    stream.expect.request(1) within 10.millis
    Thread.sleep(40)
    stream.send.onNext('d)
    drain.expect.onNext('d) within 10.millis
    stream.send.onComplete()
    drain.expect.onComplete() within 10.millis
  }
}