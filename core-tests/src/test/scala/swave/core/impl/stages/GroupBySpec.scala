/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import scala.collection.mutable.ListBuffer
import scala.util.Failure
import org.scalacheck.Gen
import org.scalatest.Inspectors
import swave.core._
import swave.testkit.TestError
import swave.testkit.gen.{TestFixture, TestOutput, TestSetup}

final class GroupBySpec extends SyncPipeSpec with Inspectors {

  implicit val env    = StreamEnv()
  implicit val config = PropertyCheckConfig(minSuccessful = 1000)

  implicit val integerInput = Gen.chooseNum(0, 999)

  "GroupBy" in check {
    testSetup
      .input[Int]
      .fixture(_.output[Spout[Int]](TestSetup.Default.nonDroppingOutputScripts))
      .fixture(fd ⇒ Gen.listOfN(16, fd.output[Int](TestSetup.Default.nonDroppingOutputScripts)))
      .param(Gen.oneOf(false, true))
      .param(Gen.oneOf(false, true))
      .prop
      .from { (in, out, allSubOuts, reopenCancelledSubs, eagerCancel) ⇒
        import TestFixture.State._

        val iter    = allSubOuts.iterator
        val subOuts = ListBuffer.empty[TestOutput[Int]]
        out.appendElemHandler { sub ⇒
          if (iter.hasNext) {
            val subOut = iter.next()
            subOuts += subOut
            inside(sub.drainTo(subOut.drain).value) {
              case Some(Failure(e)) ⇒
                if (e != TestError) e.printStackTrace()
                e shouldEqual TestError
              case _ ⇒ // ok here
            }
          } else sub.drainTo(Drain.ignore)
        }

        in.spout
          .groupBy(maxSubstreams = 256, reopenCancelledSubs, eagerCancel)(_ % 8)
          .drainTo(out.drain) shouldTerminate likeThis {
          case Cancelled ⇒ // input can be in any state
            forAll(subOuts) {
              _.terminalState should (be(Cancelled) or be(Completed) or be(Error(TestError)))
            }

          case Completed if subOuts.nonEmpty ⇒
            forAll(subOuts) {
              _.terminalState should (be(Cancelled) or be(Completed))
            }

          case Completed ⇒ in.scriptedSize shouldBe 0

          case error @ Error(TestError) ⇒
            forAll(subOuts) {
              _.terminalState should (be(Cancelled) or be(error))
            }
            in.terminalState should (be(Cancelled) or be(error))
        }

        val subResults = subOuts.map(_.received).filter(_.nonEmpty).groupBy(_.head % 8)
        val expected   = in.produced.groupBy(_                                     % 8)
        val received =
          if (reopenCancelledSubs) subResults.map { case (key, seqs) ⇒ key → seqs.flatten } else
            subResults.map { case (key, seqs)                        ⇒ key → seqs.head }
        forAll(received) {
          case (key, receivedValues) ⇒
            receivedValues shouldEqual expected(key).take(receivedValues.size)
        }
      }
  }
}
