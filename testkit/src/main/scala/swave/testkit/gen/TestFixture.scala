/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.testkit.gen

import swave.core._
import swave.testkit.impl.{ TestDrainStage, TestStage, TestStreamStage }

import scala.concurrent.Future
import scala.util.{ Failure, Success }

sealed abstract class TestFixture {
  private[testkit] def stage: TestStage

  def termination: Future[TestFixture.State.Finished] = stage.finishedState

  def terminalState: TestFixture.State.Terminal = termination.state
}

object TestFixture {

  sealed trait State
  object State {
    sealed trait Intermediate extends State
    sealed trait Terminal extends State
    sealed trait Finished extends Terminal

    case object Starting extends Intermediate
    case object Running extends Intermediate
    case object Cancelled extends Finished
    case object Completed extends Finished
    final case class Error(e: Throwable) extends Terminal
  }

  type TerminalStateValidation = State.Terminal ⇒ Unit

  implicit class RichTerminalStateFuture(val outTermination: Future[TestFixture.State.Finished]) extends AnyVal {
    def shouldTerminate(validation: TerminalStateValidation): Unit = validation(state)

    def state: TestFixture.State.Terminal =
      outTermination.value match {
        case Some(Success(noError)) ⇒ noError
        case Some(Failure(e))       ⇒ TestFixture.State.Error(e)
        case None                   ⇒ TestFixture.State.Error(new RuntimeException("Unterminated fixture"))
      }
  }
}

final class TestInput[T](private[testkit] val stage: TestStreamStage) extends TestFixture {
  def stream: Stream[T] = new Stream[T](stage)
  def produced: Vector[T] = stage.result
  def size: Int = stage.resultSize
  def scriptedSize: Int = stage.scriptedSize
  def scriptedError: Option[Throwable] = stage.termination

  override def toString: String = s"TestInput${stage.id}"

  def elements: Iterable[T] = stage.elemsIterable.asInstanceOf[Iterable[T]]
}

sealed class TestOutput[T](private[testkit] val stage: TestDrainStage) extends TestFixture {
  def drain: Drain[T, Future[TestFixture.State.Finished]] = new Drain(stage, termination)
  def received: Vector[T] = stage.result
  def size: Int = stage.resultSize
  def scriptedSize: Int = stage.scriptedSize
  def appendElemHandler(f: T ⇒ Unit): Unit = stage.appendElemHandler(f.asInstanceOf[AnyRef ⇒ Unit])

  override def toString: String = s"TestOutput${stage.id}"
}
