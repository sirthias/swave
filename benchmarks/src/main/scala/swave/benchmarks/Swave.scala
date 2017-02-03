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
    fanIn(80.mio),
    fanOut(80.mio),
    fibonacci(50.mio),
    simpleDrain(120.mio),
    substreams(30.mio)
  )

  implicit def startStreamGraph[T](streamGraph: StreamGraph[Future[Unit]]): Future[Unit] =
    streamGraph.run().result

  def fanIn(count: Long) =
    benchmark("fanIn") { showProgressAndTimeFirstElement =>
      zeros
        .attach(zeros)
        .attach(zeros)
        .attach(zeros)
        .attach(zeros)
        .fanInMerge()
        .take(count)
        .onElement(_ => showProgressAndTimeFirstElement(count))
        .async()
        .to(Drain.ignore)
    }

  def fanOut(count: Long) =
    benchmark("fanOut") { showProgressAndTimeFirstElement =>
      def blackhole = Drain.ignore.dropResult
      zeros
        .take(count)
        .onElement(_ => showProgressAndTimeFirstElement(count))
        .async()
        .fanOutBroadcast()
          .sub.to(blackhole)
          .sub.to(blackhole)
          .sub.to(blackhole)
          .sub.to(blackhole)
          .subContinue
        .to(Drain.ignore)
    }

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
      zeros
        .take(count)
        .onElement(_ => showProgressAndTimeFirstElement(count))
        .async()
        .to(Drain.ignore)
    }

  def substreams(count: Long) =
    benchmark("substreams") { showProgressAndTimeFirstElement =>
      zeros
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

  private def zeros = Spout.repeat(zero)

  override def cleanUp(): Unit = env.shutdown().awaitTermination(1.second)
}
