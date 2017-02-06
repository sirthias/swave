/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.compat.akka

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._

import scala.concurrent.duration._
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Inside, Matchers}
import swave.core._
import swave.core.util._

class AkkaCompatSpec extends FreeSpec with Matchers with Inside with BeforeAndAfterAll {

  implicit val env          = StreamEnv()
  implicit val system       = ActorSystem()
  implicit val materializer = ActorMaterializer()

  "Akka compatibility should work as expected" - {

    "Source.toSpout" in {
      Source(1 to 10).toSpout.drainToList(100).await() shouldEqual (1 to 10)
    }

    "Spout.toAkkaSource" in {
      Spout(1 to 10).toAkkaSource.runWith(Sink.seq).await() shouldEqual (1 to 10)
    }

    "Flow.toPipe" in {
      val flow = Flow[Int].map(_ * -1)
      Spout(1 to 10).via(flow.toPipe).drainToList(100).await() shouldEqual (-1 to -10 by -1)
    }

    "Pipe.toAkkaFlow" in {
      val pipe = Pipe[Int].map(_ * -1)
      Source(1 to 10).via(pipe.toAkkaFlow).runWith(Sink.seq).await() shouldEqual (-1 to -10 by -1)
    }

    "Sink.toDrain" in {
      val sink = Sink.seq[Int]
      Spout(1 to 10).drainTo(sink.toDrain).await() shouldEqual (1 to 10)
    }

    "Drain.toAkkaSink" in {
      val drain = Drain.seq[Int](100)
      Source(1 to 10).runWith(drain.toAkkaSink).await() shouldEqual (1 to 10)
    }
  }

  override val invokeBeforeAllAndAfterAllEvenIfNoTestsAreExpected = true

  override protected def afterAll(): Unit = {
    val envTermination = env.shutdown()
    system.terminate().await(2.seconds)
    envTermination.awaitTermination(2.seconds)
  }
}
