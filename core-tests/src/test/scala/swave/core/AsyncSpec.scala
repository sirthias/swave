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

import com.typesafe.config.ConfigFactory
import swave.core.util._
import scala.concurrent.duration._

class AsyncSpec extends SwaveSpec {

  val config = ConfigFactory.parseString {
    """swave.core.dispatcher.definition {
      |  default {
      |    type = thread-pool
      |    thread-pool.fixed-pool-size = 1
      |  }
      |  disp0.thread-pool.fixed-pool-size = 1
      |  disp1.thread-pool.fixed-pool-size = 1
      |  disp2.thread-pool.fixed-pool-size = 1
      |}""".stripMargin
  }
  implicit val env = StreamEnv(config = Some(config))

  def threadName = Thread.currentThread().getName

  "Asynchronous pipings" - {

    "sync base example" in {
      val (1, mapThreadName) =
        Stream(1, 2, 3)
          .map(_ → threadName)
          .drainTo(Drain.head)
          .await(20.millis)

      mapThreadName shouldEqual threadName
    }

    "single default dispatcher" in {
      val piping =
        Stream.continually(threadName)
          .map(_ → threadName)
          .to(Drain.head.async()).seal().get
      val (threadName0, threadName1) = piping.run().await(20.millis)

      List(threadName0, threadName1).distinct shouldEqual List("swave-default-1")
    }

    "single non-default dispatcher" in {
      val piping =
        Stream.continually(threadName)
          .map(_ → threadName)
          .to(Drain.head.async("disp0")).seal().get
      val (threadName0, threadName1) = piping.run().await(20.millis)

      List(threadName0, threadName1).distinct shouldEqual List("swave-disp0-1")
    }

    "default async boundary with implicit default tail" in {
      val piping =
        Stream.continually(threadName)
          .async()
          .map(_ → threadName)
          .to(Drain.head).seal().get
      val (threadName0, threadName1) = piping.run().await(20.millis)

      List(threadName0, threadName1).distinct shouldEqual List("swave-default-1")
    }

    "default async boundary with explicit default tail" in {
      val piping =
        Stream.continually(threadName)
          .async()
          .map(_ → threadName)
          .to(Drain.head.async()).seal().get
      val (threadName0, threadName1) = piping.run().await(20.millis)

      List(threadName0, threadName1).distinct shouldEqual List("swave-default-1")
    }

    "non-default async boundary with implicit default tail" in {
      val piping =
        Stream.continually(threadName)
          .async("disp0")
          .map(_ → threadName)
          .to(Drain.head).seal().get
      val (threadName0, threadName1) = piping.run().await(20.millis)

      threadName0 shouldEqual "swave-disp0-1"
      threadName1 shouldEqual "swave-default-1"
    }

    "non-default async boundary with non-default tail" in {
      val piping =
        Stream.continually(threadName)
          .async("disp0")
          .map(_ → threadName)
          .to(Drain.head.async("disp1")).seal().get
      val (threadName0, threadName1) = piping.run().await(20.millis)

      threadName0 shouldEqual "swave-disp0-1"
      threadName1 shouldEqual "swave-disp1-1"
    }

    "2 async boundaries with non-default tail" in {
      val piping =
        Stream.continually(threadName)
          .async("disp0")
          .map(_ → threadName)
          .async("disp1")
          .map(_ → threadName)
          .to(Drain.head.async("disp2")).seal().get
      // println(PipeElem.render(piping.pipeElem, showDispatchers = true))
      val ((threadName0, threadName1), threadName2) = piping.run().await(20.millis)

      threadName0 shouldEqual "swave-disp0-1"
      threadName1 shouldEqual "swave-disp1-1"
      threadName2 shouldEqual "swave-disp2-1"
    }

    "conflicting async boundaries" in {
      Stream.continually(threadName)
        .fanOutBroadcast()
        .sub.async("disp0").end
        .sub.async("disp1").end
        .fanInMerge()
        .to(Drain.head).seal().failed.get.getMessage.shouldEqual(
          "Conflicting dispatcher assignment to async region containing stage 'NopStage': [disp1] vs. [disp0]")
    }

    "conflicting async markers" in {
      Stream.continually(threadName)
        .fanOutBroadcast()
        .sub.to(Drain.cancelling.async("disp0"))
        .subContinue
        .to(Drain.head.async("disp1")).seal().failed.get.getMessage.shouldEqual(
          "Conflicting dispatcher assignment to async region containing stage 'HeadDrainStage': [disp1] vs. [disp0]")
    }

    "sync sub-stream in async parent stream" in {
      Stream.from(0)
        .inject()
        .map(_ elementAt 1)
        .flattenConcat()
        .take(5)
        .drainTo(Drain.seq(limit = 5).async()).await(50.millis) shouldEqual List(1, 3, 5, 7, 9)
    }

    "async sub-stream in async parent stream" in {
      Stream.from(0)
        .inject()
        .map(_.async(bufferSize = 0).elementAt(1))
        .flattenConcat()
        .take(5)
        .drainTo(Drain.seq(limit = 5).async()).await(50.millis) shouldEqual List(1, 3, 5, 7, 9)
    }

    "async sub-stream in sync parent stream" in {
      Stream.from(0)
        .inject()
        .map(_.tee(Drain.ignore.dropResult.async(), eagerCancel = true).elementAt(1))
        .flattenConcat()
        .take(5)
        .drainToList(5).failed.await(50.millis).getMessage.shouldEqual(
          "A synchronous parent stream must not contain an async sub-stream. " +
            "You can fix this by explicitly marking the parent stream as `async`.")
    }

    "conflicting runners in sub-stream setup" in {
      Stream.from(0)
        .inject()
        .map(_.async("disp0").elementAt(1))
        .flattenConcat()
        .take(5)
        .drainTo(Drain.seq(limit = 5).async()).failed.await(50.millis).getMessage.shouldEqual(
          "An asynchronous sub-stream with a non-default dispatcher assignment (in this case `disp0`) must be " +
            "fenced off from its parent stream with explicit async boundaries!")
    }

    "complex example" in {
      val piping =
        Stream.continually(threadName)
          .async()
          .inject()
          .map(_.take(1).map(_ :: threadName :: Nil))
          .flattenConcat()
          .to(Drain.head).seal().get
      //println(PipeElem.render(piping.pipeElem, showDispatchers = true))
      piping.run().await(20.millis).distinct shouldEqual List("swave-default-1")
    }
  }
}
