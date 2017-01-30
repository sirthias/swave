/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.benchmarks

import scala.concurrent.Future
import scala.concurrent.duration._
import swave.core._

object Swave extends BenchmarkSuite("swave") {

  implicit val env = StreamEnv()

  def createBenchmarks = Seq(
    fibonacci(20 * mio),
    simpleDrain(100 * mio),
    substreams(20 * mio)
  )

  implicit def startStreamGraph[T](streamGraph: StreamGraph[Future[Unit]]): Future[Unit] =
    streamGraph.run().result

  def fibonacci(count: Long) =
    benchmark("fibonacci") { showProgressAndTimeFirstElement =>
      val coupling = Coupling[Int]
      coupling.out
        .fanOutBroadcast(eagerCancel = true)
          .sub
            .buffer(2, Buffer.RequestStrategy.Always)
            .scan(0 -> 1) { case ((a, b), c) => (a + b) -> c }
            .map(_._1)
            .to(coupling.in)
          .subContinue
        .take(count)
        .onElement(_ => showProgressAndTimeFirstElement(count))
        .async()
        .to(Drain.ignore)
    }

  def simpleDrain(count: Long) =
    benchmark("simpleDrain") { showProgressAndTimeFirstElement =>
      val zero = new Integer(0)
      Spout
        .repeat(zero)
        .take(count)
        .onElement(_ => showProgressAndTimeFirstElement(count))
        .async()
        .to(Drain.ignore)
    }

  def substreams(count: Long) =
    benchmark("substreams") { showProgressAndTimeFirstElement =>
      val zero = new Integer(0)
        Spout
          .repeat(zero)
          .injectSequential()
          .flatMap {
            _
              .take(1000000)
              .injectSequential()
              .flatMap {
                _
                  .take(10000)
                  .injectSequential()
                  .flatMap(_.take(100))
              }
          }
          .take(count)
          .onElement(_ => showProgressAndTimeFirstElement(count))
          .async()
          .to(Drain.ignore)
    }

  override def cleanUp(): Unit = env.shutdown().awaitTermination(1.second)
}
