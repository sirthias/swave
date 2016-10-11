/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import org.scalacheck.Gen
import org.scalatest.{Inside, Inspectors}
import swave.core._
import swave.testkit.TestError
import swave.testkit.gen.{TestFixture, TestOutput}

import scala.collection.mutable.ListBuffer
import scala.util.Failure

final class SplitSpec extends SyncPipeSpec with Inspectors with Inside {

  implicit val env    = StreamEnv()
  implicit val config = PropertyCheckConfig(minSuccessful = 1000)

  implicit val integerInput = Gen.chooseNum(0, 999)
  implicit val booleanInput = Gen.oneOf(true, false)

  "SplitWhen" - {

    "state space verification" in stateSpaceVerification(Pipe[Int].splitWhen(_ < 100, _))

    "Example 1" in {
      Spout(1 to 9)
        .splitWhen(_ % 4 == 0)
        .map(_.map(_.toString).reduce(_ + _))
        .flattenConcat().drainToMkString(",").value.get.get shouldEqual "123,4567,89"
    }
  }

  "SplitAfter" - {

    "state space verification" in stateSpaceVerification(Pipe[Int].splitAfter(_ < 100, _))

    "Example 1" in {
      Spout(1 to 9)
        .splitAfter(_ % 4 == 0)
        .map(_.map(_.toString).reduce(_ + _))
        .flattenConcat().drainToMkString(",").value.get.get shouldEqual "1234,5678,9"
    }
  }

  def stateSpaceVerification(pipe: Boolean => Pipe[Int, Spout[Int]]): Unit = check {
    testSetup
      .input[Int]
      .output[Spout[Int]]
      .fixture(fd ⇒ Gen.listOfN(10, fd.output[Int]))
      .param[Boolean]
      .prop
      .from { (in, out, allSubOuts, eagerCancel) ⇒
        import TestFixture.State._

        val iter = allSubOuts.iterator
        val subOuts = ListBuffer.empty[TestOutput[Int]]
        out.appendElemHandler { sub ⇒
          if (iter.hasNext) {
            val subOut = iter.next()
            subOuts += subOut
            inside(sub.drainTo(subOut.drain).value) {
              case Some(Failure(e)) ⇒ e shouldEqual TestError
              case _ ⇒ // ok here
            }
          } else sub.drainTo(Drain.ignore)
        }

        in.spout.via(pipe(eagerCancel)).drainTo(out.drain) shouldTerminate likeThis {
          case Cancelled ⇒ // input can be in any state

          case Completed if subOuts.nonEmpty ⇒
            forAll(subOuts) {
              _.terminalState should (be(Cancelled) or be(Completed))
            }

          case Completed ⇒ in.scriptedSize shouldBe 0

          case error@Error(TestError) ⇒
            if (subOuts.nonEmpty) {
              forAll(subOuts.init) {
                _.terminalState should (be(Cancelled) or be(Completed))
              }
            }
            in.terminalState should (be(Cancelled) or be(error))
        }
      }
  }

}
