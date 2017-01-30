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
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, OverflowStrategy, SourceShape}
import akka.stream.scaladsl._
import swave.core.util._

object Akka extends BenchmarkSuite("akka") {

  implicit val system = ActorSystem()
  private val settings =
    ActorMaterializerSettings(system).withSyncProcessingLimit(Int.MaxValue).withInputBuffer(256, 256)
  implicit val materializer = ActorMaterializer(settings)

  def createBenchmarks = Seq(
    fibonacci(40 * mio),
    simpleDrain(150 * mio),
    substreams(10 * mio)
  )

  implicit def startRunnableEntity[T](graph: RunnableGraph[Future[Done]]): Future[Done] =
    graph.run()

  def fibonacci(count: Long) =
    benchmark("fibonacci") { showProgressAndTimeFirstElement =>
      // TODO: how can this graph creation be simplified?
      Source.fromGraph {
        GraphDSL.create() { implicit builder =>
          import GraphDSL.Implicits._
          val bcast = builder.add(Broadcast[Int](2, eagerCancel = true))
          bcast
            .buffer(2, OverflowStrategy.backpressure)
            .scan(0 -> 1) { case ((a, b), c) => (a + b) -> c }
            .map(_._1) ~> bcast
          SourceShape {
            bcast
              .take(count)
              .map { elem => showProgressAndTimeFirstElement(count); elem }
              .outlet
          }
        }
      }.runWith(Sink.ignore)
    }

  def simpleDrain(count: Long) =
    benchmark("simpleDrain") { showProgressAndTimeFirstElement =>
      val zero = new Integer(0)
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
      val zero = new Integer(0)
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
