/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.testkit.impl

import scala.collection.immutable.VectorBuilder
import scala.concurrent.{Future, Promise}
import swave.core.impl.stages.StageImpl
import swave.testkit.gen.TestFixture

private[testkit] trait TestStage extends StageImpl {

  private[this] val resultBuilder = new VectorBuilder[AnyRef]
  private[this] var _resultSize   = 0

  private[this] var _fixtureState: TestFixture.State = TestFixture.State.Starting
  private[this] val _finishedState                   = Promise[TestFixture.State.Finished]()

  private[this] var onElem: AnyRef ⇒ Unit = x ⇒ ()

  def fixtureState: TestFixture.State = _fixtureState
  def fixtureState_=(value: TestFixture.State): Unit = {
    value match {
      case TestFixture.State.Cancelled ⇒ _finishedState.success(TestFixture.State.Cancelled)
      case TestFixture.State.Completed ⇒ _finishedState.success(TestFixture.State.Completed)
      case TestFixture.State.Error(e)  ⇒ _finishedState.failure(e)
      case _                           ⇒
    }
    _fixtureState = value
  }
  def finishedState: Future[TestFixture.State.Finished] = _finishedState.future

  private[testkit] final def result[T]: Vector[T] = resultBuilder.result().asInstanceOf[Vector[T]]
  private[testkit] final def resultSize: Int      = _resultSize

  protected final def recordElem(elem: AnyRef): Unit = {
    resultBuilder += elem
    _resultSize += 1
    onElem(elem)
  }

  def appendElemHandler(f: AnyRef ⇒ Unit): Unit = {
    val prev = onElem
    onElem = { elem ⇒
      prev(elem)
      f(elem)
    }
  }

  def id: Int

  def formatLong: String

  def scriptedSize: Int
}
