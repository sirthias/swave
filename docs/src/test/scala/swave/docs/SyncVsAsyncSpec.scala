/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import org.scalatest.{FreeSpec, Matchers}

class SyncVsAsyncSpec extends FreeSpec with Matchers {

  "the examples in the `sync-vs-async` chapter should work as expected" - {

    "stream-failure" in {
      //#stream-failure
      import scala.concurrent.Future
      import scala.util.Failure
      import swave.core._

      implicit val env = StreamEnv()

      val result: Future[Seq[Int]] =
        Spout.ints(0)
          .map(i => 1000 / (i - 10))
          .take(50)
          .to(Drain.seq(limit = 50)).run().result // shortcut: .drainToVector(limit = 50)

      result.value.get shouldBe a [Failure[_]]

      the [ArithmeticException] thrownBy result.value.get.get should have message "/ by zero"
      //#stream-failure
    }

    "base-example" in {
      //#base-example
      import scala.concurrent.Future
      import swave.core._

      implicit val env = StreamEnv()

      val result: Future[List[String]] =
        Spout.ints(0)
          .filter(_ % 5 == 0)
          .map(_.toString)
          .take(10)
          .drainToList(limit = 100) // shortcut for `to(...).run().result`

      result.value.get.get shouldEqual (0 to 45 by 5).map(_.toString)
      //#base-example
    }

    "async" in {
      //#async
      import scala.concurrent.Future
      import swave.core.util._
      import swave.core._

      implicit val env = StreamEnv()

      val result: Future[Seq[String]] =
        Spout.ints(0)
          .filter(_ % 5 == 0)
          .map(_.toString)
          .take(10)
          .async()
          .drainToList(limit = 100) // shortcut for `to(...).run().result`

      result.await() shouldEqual (0 to 45 by 5).map(_.toString)

      env.shutdown()
      //#async
    }

    "withCompletionTimeout" in {
      //#withCompletionTimeout
      import scala.concurrent.duration._
      import scala.concurrent.Future
      import swave.core.util._
      import swave.core._

      implicit val env = StreamEnv()

      val result: Future[List[String]] =
        Spout.ints(0)
          .filter(_ % 5 == 0)
          .map(_.toString)
          .take(10)
          .withCompletionTimeout(1.second) // cannot run synchronously
          .drainToList(limit = 100)

      result.await() shouldEqual (0 to 45 by 5).map(_.toString)

      env.shutdown()
      //#withCompletionTimeout
    }

    "async-boundary" in {
      //#async-boundary
      import scala.concurrent.Future
      import swave.core.util._
      import swave.core._

      implicit val env = StreamEnv()

      val run: StreamRun[Future[Seq[String]]] =
        Spout.ints(0)
          .filter(_ % 5 == 0)
          .asyncBoundary() // adds an explicit async boundary here
          .map(_.toString)
          .take(10)
          .to(Drain.seq(limit = 100)).run()

      run.result.await() shouldEqual (0 to 45 by 5).map(_.toString)

      env.shutdownOn(run.termination)
      //#async-boundary
    }

    "complex-example" in {
      //#complex-example
      import com.typesafe.config.ConfigFactory
      import scala.concurrent.{Future, Promise}
      import scala.concurrent.duration._
      import swave.core.util._
      import swave.core._

      val config = ConfigFactory.parseString {
        """swave.core.dispatcher.definition {
          |  foo.thread-pool.fixed-pool-size = 4
          |  bar.thread-pool.fixed-pool-size = 4
          |}""".stripMargin
      }
      implicit val env = StreamEnv(config = Some(config))

      def upperChars(s: String): Spout[Char] =
        Spout(s.iterator).map(_.toUpper)

      def drain(promise: Promise[String]): Drain[Char, Unit] =
        Drain.mkString(limit = 100)
          .captureResult(promise)
          .async("bar") // same as adding `.async` to all regions this drain is placed in

      val result2 = Promise[String]()
      val run: StreamRun[Future[String]] =
        upperChars("Hello")
          .asyncBoundary("foo")
          .fanOutBroadcast()
            .sub.drop(2).concat(upperChars("-Friend-").asyncBoundary()).end
            .sub.take(2).asyncBoundary().multiply(2).end
          .fanInConcat()
          .tee(Pipe[Char].asyncBoundary().deduplicate.to(drain(result2)))
          .map(_.toLower)
          .to(Drain.mkString(limit = 100))
          .run() // not using `.drainTo(...)` allows us access to the `StreamRun`

      run.result.await(5.seconds) shouldEqual "llo-friend-hhee"
      result2.future.await(5.seconds) shouldEqual "LO-FRIEND-HE"

      // only shut down when all regions have properly terminated
      env.shutdownOn(run.termination)
      //#complex-example
    }
  }
}
