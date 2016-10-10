/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import scala.concurrent.duration._
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest._
import swave.core.util._

abstract class SwaveSpec extends FreeSpec with StreamEnvShutdown {
  type Timeout = SwaveSpec.Timeout
  def Timeout = SwaveSpec.Timeout

  def produce[T](expected: T*)(implicit timeout: Timeout = Timeout()): Matcher[Spout[T]] = produceSeq(expected)
  def produceSeq[T](expected: Seq[T])(implicit timeout: Timeout = Timeout()): Matcher[Spout[T]] =
    equal(expected).matcher[Seq[T]].compose(_.drainTo(Drain.seq(100)).await(timeout.duration))
  def produceError[T](expected: Throwable)(implicit timeout: Timeout = Timeout()): Matcher[Spout[T]] =
    equal(expected).matcher[Throwable].compose(_.drainTo(Drain.ignore).failed.await(timeout.duration))
  def produceErrorLike[T](pf: PartialFunction[Throwable, Unit])(
      implicit timeout: Timeout = Timeout()): Matcher[Spout[T]] =
    new Matcher[Spout[T]] {
      def apply(left: Spout[T]) = {
        val error = left.drainTo(Drain.ignore).failed.await(timeout.duration)
        inside(error)(pf)
        MatchResult(true, "", "")
      }
    }

  def timed[U](block: ⇒ U): FiniteDuration = {
    val start = System.nanoTime()
    block
    (System.nanoTime() - start).nanos
  }
}

object SwaveSpec {
  final case class Timeout(duration: FiniteDuration = Duration.Zero)
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
