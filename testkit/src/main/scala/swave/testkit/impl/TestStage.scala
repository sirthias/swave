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

package swave.testkit.impl

import swave.testkit.TestFixture

import scala.collection.immutable.VectorBuilder
import scala.concurrent.{ Future, Promise }
import swave.core.impl.stages.Stage

private[testkit] trait TestStage { this: Stage ⇒

  private[this] val resultBuilder = new VectorBuilder[AnyRef]
  private[this] var _resultSize = 0

  private[this] var _fixtureState: TestFixture.State = TestFixture.State.Starting
  private[this] val _finishedState = Promise[TestFixture.State.Finished]()

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
  private[testkit] final def resultSize: Int = _resultSize

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