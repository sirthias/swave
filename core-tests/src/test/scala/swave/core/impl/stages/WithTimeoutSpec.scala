/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import swave.core._

final class WithTimeoutSpec extends SwaveSpec {

  val config = ConfigFactory.parseString {
    """swave.core.dispatcher.definition {
      |  default {
      |    type = thread-pool
      |    thread-pool.fixed-pool-size = 1
      |  }
      |  disp0.thread-pool.fixed-pool-size = 1
      |  disp1.thread-pool.fixed-pool-size = 1
      |  disp2.thread-pool.fixed-pool-size = 1
      |}""".stripMargin
  }
  implicit val env = StreamEnv(config = Some(config))

  "withIdleTimeout must" - {
    implicit val testTimeout = Timeout(1.second)

    "be transparent if upstream rate is sufficient" taggedAs NotOnTravis in {
      Spout.fromIterable(1 to 10).throttle(1, per = 50.millis).withIdleTimeout(100.millis) should produceSeq(1 to 10)
    }

    "fail with StreamTimeoutException if upstream rate is insufficient" taggedAs NotOnTravis in {
      Spout
        .fromIterable(1 to 10)
        .delay(x ⇒ if (x == 3) 200.millis else Duration.Zero)
        .withIdleTimeout(100.millis) should produceErrorLike {
        case x: StreamTimeoutException ⇒
          x.getMessage shouldEqual "No elements passed in the last 100 milliseconds"
      }
    }
  }

  "withInitialTimeout must" - {
    implicit val testTimeout = Timeout(1.second)

    "be transparent if first element arrives quickly enough" taggedAs NotOnTravis in {
      Spout
        .fromIterable(1 to 10)
        .delay(x ⇒ if (x == 3) 200.millis else Duration.Zero)
        .withInitialTimeout(100.millis) should produceSeq(1 to 10)
    }

    "fail with StreamTimeoutException if first element is overly delayed" taggedAs NotOnTravis in {
      Spout
        .fromIterable(1 to 10)
        .delay(x ⇒ if (x == 1) 200.millis else Duration.Zero)
        .withInitialTimeout(100.millis) should produceErrorLike {
        case x: StreamTimeoutException ⇒
          x.getMessage shouldEqual "The first element was not received within 100 milliseconds"
      }
    }
  }

  "withCompletionTimeout must" - {
    implicit val testTimeout = Timeout(1.second)

    "be transparent if stream completes quickly enough" taggedAs NotOnTravis in {
      Spout.fromIterable(1 to 10).withCompletionTimeout(100.millis) should produceSeq(1 to 10)
    }

    "fail with StreamTimeoutException if stream doesn't complete within timeout" taggedAs NotOnTravis in {
      Spout
        .fromIterable(1 to 10)
        .throttle(1, per = 20.millis)
        .withCompletionTimeout(100.millis) should produceErrorLike {
        case x: StreamTimeoutException ⇒
          x.getMessage shouldEqual "The stream was not completed within 100 milliseconds"
      }
    }
  }
}
