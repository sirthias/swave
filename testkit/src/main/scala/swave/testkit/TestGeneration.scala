/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.testkit

import org.scalacheck.Gen
import swave.testkit.gen._

trait TestGeneration {

  def testSetup: TestSetup.TestSetupDef =
    TestSetup.newDslRoot

  def asScripted(in: TestInput[_]): TestFixture.TerminalStateValidation = { outTermination ⇒
    import TestFixture.State._
    val inTermination = in.terminalState
    outTermination match {
      case Cancelled ⇒ // input can be in any state
      case Completed ⇒
        if (inTermination == Error(TestError)) sys.error(s"Input error didn't propagate to stream output")
      case x @ Error(e) ⇒ if (inTermination != x) throw e
    }
  }

  def likeThis(pf: PartialFunction[TestFixture.State.Terminal, Unit]): TestFixture.TerminalStateValidation =
    outTermination ⇒
      pf.applyOrElse(outTermination, {
        case TestFixture.State.Error(e)      ⇒ throw e
        case (x: TestFixture.State.Terminal) ⇒ sys.error(s"Stream termination `$x` did not match expected pattern!")
      }: (TestFixture.State.Terminal ⇒ Nothing))

  def withError(expected: Throwable): TestFixture.TerminalStateValidation = {
    case TestFixture.State.Error(`expected`) ⇒ // ok
    case x                                   ⇒ sys.error(s"Stream termination `$x` did not hold expected error `$expected`")
  }

  def withErrorLike(pf: PartialFunction[Throwable, Unit]): TestFixture.TerminalStateValidation = {
    case TestFixture.State.Error(e) if pf.isDefinedAt(e) ⇒ pf(e)
    case x                                               ⇒ sys.error(s"Stream termination `$x` did not match expected error pattern!")
  }

  def scriptedElementCount(in: TestInput[_], out: TestOutput[_]): Int =
    math.min(in.scriptedSize, out.scriptedSize)

  private val baseIntegerInput = Gen.chooseNum(0, 999)
  def nonOverlappingIntTestInputs(fd: TestSetup.FixtureDef, minCount: Int, maxCount: Int): Gen[List[TestInput[Int]]] =
    Gen
      .chooseNum(2, 4)
      .flatMap(count ⇒ {
        val list = List.tabulate(count)(ix ⇒ fd.input(baseIntegerInput.map(_ + ix * 1000)))
        Gen.sequence[List[TestInput[Int]], TestInput[Int]](list)
      })
}
