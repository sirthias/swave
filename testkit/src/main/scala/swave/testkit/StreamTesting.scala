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

package swave.testkit

trait StreamTesting {

  def testSetup: TestSetup.TestSetupDef =
    TestSetup.newDslRoot

  def asScripted(in: TestInput[_]): TestFixture.TerminalStateValidation = { outTermination ⇒
    import TestFixture.State._
    val inTermination = in.terminalState
    outTermination match {
      case Cancelled ⇒ // input can be in any state
      case Completed ⇒ if (inTermination == Error(TestError)) sys.error(s"Input error didn't propagate to stream output")
      case error     ⇒ if (inTermination != error) sys.error(s"Unexpected stream output error: " + error)
    }
  }

  def likeThis(pf: PartialFunction[TestFixture.State.Terminal, Unit]): TestFixture.TerminalStateValidation =
    outTermination ⇒ pf.applyOrElse(
      outTermination,
      (x: TestFixture.State.Terminal) ⇒ sys.error(s"Stream termination `$x` did not match expected pattern!"))

  def withError(expected: Throwable): TestFixture.TerminalStateValidation = {
    case TestFixture.State.Error(`expected`) ⇒ // ok
    case x                                   ⇒ sys.error(s"Stream termination `$x` did not hold expected error `$expected`")
  }

  def withErrorLike(pf: PartialFunction[Throwable, Unit]): TestFixture.TerminalStateValidation = {
    case TestFixture.State.Error(e) if pf.isDefinedAt(e) ⇒ pf(e)
    case x ⇒ sys.error(s"Stream termination `$x` did not match expected error pattern!")
  }

  def scriptedElementCount(in: TestInput[_], out: TestOutput[_]): Int =
    math.min(in.scriptedSize, out.scriptedSize)
}