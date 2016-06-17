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

package swave.examples.pi

import akka.NotUsed
import scala.util.{Failure, Success}
import swave.core.util.XorShiftRandom
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializerSettings, FlowShape, ActorMaterializer}
import akka.stream.scaladsl._

object AkkaPi extends App {
  implicit val system = ActorSystem("AkkaPi")
  import system.dispatcher

  private val settings = ActorMaterializerSettings(system)
    .withSyncProcessingLimit(Int.MaxValue)
    .withInputBuffer(256, 256)
  implicit val materializer = ActorMaterializer(settings)

  val random = XorShiftRandom()

  Source.fromIterator(() ⇒ Iterator.continually(random.nextDouble()))
    .grouped(2)
    .map { case x +: y +: Nil ⇒ Point(x, y) }
    .via(broadcastFilterMerge)
    .async
    .scan(State(0, 0)) { _ withNextSample _ }
    .splitWhen(_.totalSamples % 1000000 == 1)
    .drop(999999)
    .concatSubstreams
    .map(state ⇒ f"After ${state.totalSamples}%,10d samples π is approximated as ${state.π}%.6f")
    .take(30)
    .runForeach(println)
    .onComplete {
      case Success(_) =>
        val time = System.currentTimeMillis() - system.startTime
        println(f"Done. Total time: $time%,6d ms, Throughput: ${30000.0/time}%.3fM samples/sec\n")
        system.terminate()

      case Failure(e) => println(e)
    }

  println("Main thread exit.")

  ///////////////////////////////////////////

  def broadcastFilterMerge: Flow[Point, Sample, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val broadcast = b.add(Broadcast[Point](2)) // split one upstream into 2 downstreams
      val filterInner = b.add(Flow[Point].filter(_.isInner).map(_ => InnerSample))
      val filterOuter = b.add(Flow[Point].filterNot(_.isInner).map(_ => OuterSample))
      val merge = b.add(Merge[Sample](2)) // merge 2 upstreams into one downstream

      broadcast.out(0) ~> filterInner ~> merge.in(0)
      broadcast.out(1) ~> filterOuter ~> merge.in(1)

      FlowShape(broadcast.in, merge.out)
    })

  //////////////// MODEL ///////////////

  case class Point(x: Double, y: Double) {
    def isInner: Boolean = x * x + y * y < 1.0
  }

  sealed trait Sample
  case object InnerSample extends Sample
  case object OuterSample extends Sample

  case class State(totalSamples: Long, inCircle: Long) {
    def π: Double = (inCircle.toDouble / totalSamples) * 4.0
    def withNextSample(sample: Sample) =
      State(totalSamples + 1, if (sample == InnerSample) inCircle + 1 else inCircle)
  }
}
