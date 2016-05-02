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

package swave.core

import scala.concurrent.duration._
import org.scalatest.matchers.Matcher
import org.scalatest.{ Inside, BeforeAndAfterAll, FreeSpec, Matchers }
import swave.core.util._

abstract class SwaveSpec extends FreeSpec with Matchers with Inside with BeforeAndAfterAll {

  implicit val env: StreamEnv
  override val invokeBeforeAllAndAfterAllEvenIfNoTestsAreExpected = true

  override protected def afterAll(): Unit =
    env.shutdown().awaitTermination(2.seconds)

  def produce[T](expected: T*): Matcher[Stream[T]] = produceSeq(expected)
  def produceSeq[T](expected: Seq[T]): Matcher[Stream[T]] =
    equal(expected).matcher[Seq[T]].compose(_.drainTo(Drain.seq(100)).await(Duration.Zero))
}
