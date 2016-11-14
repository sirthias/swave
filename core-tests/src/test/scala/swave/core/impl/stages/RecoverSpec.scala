/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import org.scalacheck.Gen
import swave.core._
import swave.core.internal.testkit.TestError
import swave.testkit.Probes._

/**
  * Almost directly transcribed from akka-stream's FlowRecoverWithSpec which carries this copyright:
  *
  *    Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
  */
final class RecoverSpec extends SyncPipeSpec {

  implicit val env          = StreamEnv()
  implicit val config       = PropertyCheckConfig(minSuccessful = 100)
  implicit val integerInput = Gen.chooseNum(0, 999)

  "RecoverWith" - {

    "Xrecover when there is a matching handler" in {
      Spout(1 to 4)
        .map { a ⇒ if (a == 3) throw TestError else a }
        .recoverWith(4) { case TestError ⇒ Spout(0, -1) }
        .drainTo(DrainProbe[Int])
        .get
        .sendRequest(5)
        .expectNext(1, 2, 0, -1)
        .expectComplete()
        .verifyCleanStop()
    }

    "cancel sub-stream if parent is terminated when there is a matching handler" in {
      Spout(1 to 4).map { a ⇒ if (a == 3) throw TestError else a }
        .recoverWith(4) { case _: Throwable ⇒ Spout(0, -1) }
        .drainTo(DrainProbe[Int])
        .get
        .sendRequest(2)
        .expectNext(1, 2)
        .sendRequest(1)
        .expectNext(0)
        .sendCancel()
        .verifyCleanStop()
    }

    "fail stream if handler doesn't match" in {
      Spout(1 to 3).map { a ⇒ if (a == 2) throw TestError else a }
        .recoverWith(4) { case _: IndexOutOfBoundsException ⇒ Spout.one(0) }
        .drainTo(DrainProbe[Int])
        .get
        .sendRequest(1)
        .expectNext(1)
        .sendRequest(1)
        .expectError(TestError)
        .verifyCleanStop()
    }

    "be transparent when there is no matching exception" in check {
      testSetup
        .input[Int]
        .output[Int]
        .prop.from { (in, out) ⇒
        in.spout
          .recoverWith(4) { case _: IndexOutOfBoundsException ⇒ Spout.one(0) }
          .drainTo(out.drain) shouldTerminate asScripted(in)

        out.received shouldEqual in.produced.take(out.scriptedSize)
      }
    }

    "switch the second time if alternative source throws exception" in {
      Spout(1 to 3).map { a ⇒ if (a == 3) throw new IndexOutOfBoundsException else a }
        .recoverWith(4) {
          case _: IndexOutOfBoundsException ⇒ Spout(11, 22).map(m ⇒ if (m == 22) throw TestError else m)
          case TestError                    ⇒ Spout(33, 44)
        }
        .drainTo(DrainProbe[Int])
        .get
        .sendRequest(2)
        .expectNext(1, 2)
        .sendRequest(2)
        .expectNext(11, 33)
        .sendRequest(2)
        .expectNext(44)
        .expectComplete()
        .verifyCleanStop()
    }

    "terminate with exception if partial function fails to match after an alternative source failure" in {
      Spout(1 to 3).map { a ⇒ if (a == 3) throw new IllegalArgumentException else a }
        .recoverWith(4) {
          case _: IllegalArgumentException ⇒ Spout(11, 22).map(m ⇒ if (m == 22) throw TestError else m)
        }
        .drainTo(DrainProbe[Int])
        .get
        .sendRequest(2)
        .expectNext(1, 2)
        .sendRequest(1)
        .expectNext(11)
        .sendRequest(1)
        .expectError(TestError)
        .verifyCleanStop()
    }

    "respect maxRetries parameter" in {
      Spout(1 to 3).map { a ⇒ if (a == 3) throw TestError else a }
        .recoverWith(3) { case TestError ⇒ Spout(11, 22) concat Spout.failing(TestError, eager = false) }
        .drainTo(DrainProbe[Int])
        .get
        .sendRequest(2)
        .expectNext(1, 2)
        .sendRequest(2)
        .expectNext(11, 22)
        .sendRequest(2)
        .expectNext(11, 22)
        .sendRequest(2)
        .expectNext(11, 22)
        .sendRequest(1)
        .expectError(TestError)
        .verifyCleanStop()
    }
  }

  "Recover" - {
    "recover when there is a matching handler" in {
      Spout(1 to 4).map { a ⇒ if (a == 3) throw TestError else a }
        .recover { case t: Throwable ⇒ 0 }
        .drainTo(DrainProbe[Int])
        .get
        .sendRequest(2)
        .expectNext(1, 2)
        .sendRequest(2)
        .expectNext(0)
        .expectComplete()
    }

    "failed stream if handler is not for such exception type" in {
      Spout(1 to 3).map { a ⇒ if (a == 2) throw TestError else a }
        .recover { case t: IndexOutOfBoundsException ⇒ 0 }
        .drainTo(DrainProbe[Int])
        .get
        .sendRequest(1)
        .expectNext(1)
        .sendRequest(1)
        .expectError(TestError)
    }

    "not influence stream when there is no exceptions" in {
      Spout(1 to 3)
        .recover { case t: Throwable ⇒ 0 }
        .drainTo(DrainProbe[Int])
        .get
        .sendRequest(3)
        .expectNext(1, 2, 3)
        .expectComplete()
    }
  }
}
