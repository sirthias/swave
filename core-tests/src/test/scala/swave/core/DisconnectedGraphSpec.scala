/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import swave.testkit.Probes._

class DisconnectedGraphSpec extends SwaveSpec {

  implicit val env = StreamEnv()

  "Disconnected graphs (forests) should" - {

    "work as expected" in {
      val drain = Drain.toPublisher[Int]()
      val spout = Spout.fromPublisher(drain.result)

      Spout(1, 2, 3)
        .map(_ * 2)
        .via(Pipe.fromDrainAndSpout(drain.dropResult, spout))
        .filter(_ < 10)
        .drainTo(DrainProbe[Int]).get
        .sendRequest(5)
        .expectNext(2, 4, 6)
        .expectComplete()
        .verifyCleanStop()
    }
  }
}
