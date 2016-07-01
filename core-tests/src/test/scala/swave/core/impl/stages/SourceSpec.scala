/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages

import org.scalacheck.Gen
import org.scalatest.Inspectors
import swave.core._

final class SourceSpec extends SyncPipeSpec with Inspectors {

  implicit val env = StreamEnv()
  implicit val config = PropertyCheckConfig(minSuccessful = 1000)

  implicit val integerInput = Gen.chooseNum(0, 999)

  "Source.lazy" in check {
    testSetup
      .input[Int]
      .output[String]
      .prop.from { (in, out) ⇒

        Stream.lazyStart(() ⇒ in.stream)
          .map(_.toString)
          .drainTo(out.drain) shouldTerminate asScripted(in)

        out.received shouldEqual in.produced.take(out.scriptedSize).map(_.toString)
      }
  }
}
