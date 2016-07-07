/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.concurrent.duration._
import org.scalatest.matchers.Matcher
import org.scalatest._
import swave.core.util._

abstract class SwaveSpec extends FreeSpec with StreamEnvShutdown {

  def produce[T](expected: T*): Matcher[Stream[T]] = produceSeq(expected)
  def produceSeq[T](expected: Seq[T]): Matcher[Stream[T]] =
    equal(expected).matcher[Seq[T]].compose(_.drainTo(Drain.seq(100)).await(Duration.Zero))
}

trait StreamEnvShutdown extends Matchers with Inside with BeforeAndAfterAll { this: Suite ⇒

  implicit val env: StreamEnv

  override val invokeBeforeAllAndAfterAllEvenIfNoTestsAreExpected = true

  override protected def afterAll(): Unit = {
    env.shutdown().awaitTermination(2.seconds)
    afterTermination()
  }

  protected def afterTermination(): Unit = ()
}
