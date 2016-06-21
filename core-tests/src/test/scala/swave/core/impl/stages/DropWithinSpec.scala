/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

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

    stream.dropWithin(50.millis).drainTo(drain) shouldEqual Try(())

    drain.send.request(5)
    stream.expect.request(5) within 20.millis
    stream.send.onNext('a)
    stream.expect.request(1) within 20.millis
    stream.send.onNext('b)
    stream.expect.request(1) within 20.millis
    stream.send.onNext('c)
    stream.expect.request(1) within 20.millis
    Thread.sleep(60)
    stream.send.onNext('d)
    drain.expect.onNext('d) within 20.millis
    stream.send.onComplete()
    drain.expect.onComplete() within 20.millis
  }
}
