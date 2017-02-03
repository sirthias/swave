/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.benchmarks

import scala.concurrent.Future
import scala.concurrent.duration._
import akka.Done
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import swave.core.util._

object Akka extends BenchmarkSuite("akka") {

  implicit val system = ActorSystem()
  private val settings =
    ActorMaterializerSettings(system).withSyncProcessingLimit(Int.MaxValue).withInputBuffer(256, 256)
  implicit val materializer = ActorMaterializer(settings)

  def createBenchmarks = Seq(
    fanIn(50.mio),
    fanOut(30.mio),
    fibonacci(30.mio),
    simpleDrain(100.mio),
    substreams(6.mio)
  )

  implicit def startRunnableEntity[T](graph: RunnableGraph[Future[Done]]): Future[Done] =
    graph.run()

  def fanIn(count: Long) =
    benchmark("fanIn") { showProgressAndTimeFirstElement =>
      RunnableGraph.fromGraph {
        GraphDSL.create(Sink.ignore) { implicit builder => sink =>
          import GraphDSL.Implicits._
          val zeros = Source.repeat(zero)
          val merge = builder.add(Merge[Integer](5))
          for (ix <- 0 to 4) { zeros ~> merge.in(ix) }
          merge.out
            .take(count)
            .map { elem => showProgressAndTimeFirstElement(count); elem } ~> sink
          ClosedShape
        }
      }
    }

  def fanOut(count: Long) =
    benchmark("fanOut") { showProgressAndTimeFirstElement =>
      RunnableGraph.fromGraph {
        GraphDSL.create(Sink.ignore) { implicit builder => sink =>
          import GraphDSL.Implicits._
          val fanOut = builder.add(Broadcast[Integer](5))
          Source
            .repeat(zero)
            .take(count)
            .map { elem => showProgressAndTimeFirstElement(count); elem } ~> fanOut.in
          fanOut.out(0) ~> sink
          for (ix <- 1 to 4) { fanOut.out(ix) ~> Sink.ignore }
          ClosedShape
        }
      }
    }

  def fibonacci(count: Long) =
    benchmark("fibonacci") { showProgressAndTimeFirstElement =>
      RunnableGraph.fromGraph {
        GraphDSL.create(Sink.ignore) { implicit builder => sink =>
          import GraphDSL.Implicits._
          val bcast = builder.add(Broadcast[Int](2, eagerCancel = true))
          bcast.out(0)
            .buffer(2, OverflowStrategy.backpressure)
            .scan(0 -> 1) { case ((a, b), c) => (a + b) -> c }
            .map(_._1) ~> bcast.in
          bcast.out(1)
            .take(count)
            .map { elem => showProgressAndTimeFirstElement(count); elem } ~> sink
          ClosedShape
        }
      }
    }

  def simpleDrain(count: Long) =
    benchmark("simpleDrain") { showProgressAndTimeFirstElement =>
      Source
        .repeat(zero)
        .take(count)
        .map { elem => showProgressAndTimeFirstElement(count); elem }
        .toMat(Sink.ignore)(Keep.right)
    }

  def substreams(count: Long) =
    benchmark("substreams") { showProgressAndTimeFirstElement =>
      def everyNthElement(c: Int) =
        new (Integer => Boolean) {
          private[this] var _count: Int = c
          def apply(i: Integer): Boolean = if (_count == 1) { _count = c; true } else { _count -= 1; false }
        }
      Source
        .repeat(zero)
        .splitAfter(everyNthElement(1000000))
        .splitAfter(everyNthElement(10000))
        .splitAfter(everyNthElement(100))
        .concatSubstreams
        .concatSubstreams
        .concatSubstreams
        .take(count)
        .map { elem => showProgressAndTimeFirstElement(count); elem }
        .toMat(Sink.ignore)(Keep.right)
    }

  override def cleanUp(): Unit = {
    system.terminate().await(1.second)
    ()
  }
}
