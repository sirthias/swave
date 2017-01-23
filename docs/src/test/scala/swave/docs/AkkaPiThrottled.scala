/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import scala.util.{Failure, Success}
import scala.concurrent.duration._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, FlowShape, ThrottleMode}
import swave.core.util.XorShiftRandom

object AkkaPiThrottled extends App {
  implicit val system = ActorSystem("AkkaPi")
  import system.dispatcher

  private val settings = ActorMaterializerSettings(system)
  implicit val materializer = ActorMaterializer(settings)

  val random = XorShiftRandom()

  Source
    .fromIterator(() ⇒ Iterator.continually(random.nextDouble()))
    .grouped(2)
    .map { case x +: y +: Nil ⇒ Point(x, y) }
    .via(broadcastFilterMerge)
    .scan(State(0, 0)) { _ withNextSample _ }
    .conflate(Keep.right)
    .throttle(1, 1.second, 0, ThrottleMode.Shaping)
    .map { state ⇒ println(f"After ${state.totalSamples}%,10d samples π is approximated as ${state.π}%.6f"); state }
    .take(20)
    .runWith(Sink.last)
    .onComplete {
      case Success(State(totalSamples, _)) =>
        val time = System.currentTimeMillis() - system.startTime
        val throughput = totalSamples / 1000.0 / time
        println(f"Done. Total time: $time%,6d ms, Throughput: $throughput%.3fM samples/sec\n")
        system.terminate()

      case Failure(e) => println(e)
    }

  println("Main thread exit.")

  ///////////////////////////////////////////

  def broadcastFilterMerge: Flow[Point, Sample, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val broadcast   = b.add(Broadcast[Point](2)) // split one upstream into 2 downstreams
    val filterInner = b.add(Flow[Point].filter(_.isInner).map(_ => InnerSample))
      val filterOuter = b.add(Flow[Point].filterNot(_.isInner).map(_ => OuterSample))
      val merge       = b.add(Merge[Sample](2)) // merge 2 upstreams into one downstream

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
