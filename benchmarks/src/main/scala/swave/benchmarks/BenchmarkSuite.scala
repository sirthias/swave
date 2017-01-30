/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.benchmarks

import scala.concurrent.Future
import scala.concurrent.duration._
import swave.core.util._

abstract class BenchmarkSuite(val suiteName: String) {

  private final val ITERATIONS = 3
  private final val DROPMARGIN = 0
  private final var nameWidth: Int = _

  def createBenchmarks: Seq[Benchmark[_]]

  def cleanUp(): Unit

  def benchmark[T](name: String)(create: (Long => Unit) => T)(implicit start: T => Future[Any]): Benchmark[T] =
    new Benchmark(name, create)

  final class Benchmark[T](val name: String, create: (Long => Unit) => T)(implicit start: T => Future[Any]) {
    @volatile private[this] var startTimeStamp: Long = _
    private[this] var elementCount: Long = _
    private[this] var timeToFirstElementNanos: Long = _
    private[this] var lastProgressShown: Long = _

    private[this] val showProgressAndTimeFirstElement: Long => Unit = { totalElementCount =>
      val timeStamp = now
      if (timeToFirstElementNanos == 0) timeToFirstElementNanos = timeStamp - startTimeStamp
      elementCount += 1
      if (timeStamp - lastProgressShown > 1000000000) {
        val elapsed = timeStamp - startTimeStamp
        val tp = (elementCount / 1000.0) / (elapsed / 1000000.0)
        println(f"$suiteName ${pad(name)}: Avg. throughput after $elementCount%,10d elements is $tp%5.2fM elems/sec")
        lastProgressShown = timeStamp
      }
      if (elementCount == totalElementCount) startTimeStamp = 0 // publish all variables to other threads
    }

    def run(): BenchmarkResult = {
      val runnable = create(showProgressAndTimeFirstElement)
      val localStartTime = now
      startTimeStamp = localStartTime // publish to other threads
      val future = start(runnable)
      val timeToStart = elapsedSince(localStartTime)
      future.await(1.minute)
      val totalTime = elapsedSince(localStartTime)
      if (startTimeStamp == 0) { // make sure we read the published variable values
        new BenchmarkResult(name, elementCount, totalTime, timeToStart, timeToFirstElementNanos.nanos)
      } else sys.error(s"totalElementCount appears to not have been reached in benchmark `$name`")
    }
  }

  final class BenchmarkResult(val name: String,
                              val elements: Long,
                              val totalTime: FiniteDuration,
                              val timeToStart: FiniteDuration,
                              val timeToFirstElement: FiniteDuration) {
    val throughputInMioPerSec: Double = elements / 1000.0 / totalTime.toMillis
  }

  final def main(args: Array[String]): Unit = {
    nameWidth = createBenchmarks.map(_.name.length).max
    val random = XorShiftRandom()

    def medianAndRelativeError(results: Seq[Double]): (Double, Double) = {
      val effectiveResults = results.sorted
      val min = effectiveResults(DROPMARGIN)
      val median = effectiveResults(effectiveResults.size / 2)
      val max = effectiveResults(effectiveResults.size - DROPMARGIN - 1)
      val relativeError = math.max((median - min) / median, (max - median) / median) * 100
      median -> relativeError
    }

    val result: Seq[String] =
      Seq.tabulate(ITERATIONS) { iteration =>
        println(s"\n----- ITERATION ${iteration + 1}/$ITERATIONS -----")
        val benchmarks = createBenchmarks.toArray
        random.shuffle_!(benchmarks)
        benchmarks.map { benchmark =>
          val result = benchmark.run()
          println("---")
          result
        }
      }
      .flatten
      .groupBy(_.name)
      .toList
      .sortBy(_._1)
      .map { case (name, results) =>
        val (throughput, relativeThroughputError) =
          medianAndRelativeError(results.map(_.throughputInMioPerSec))
        val (timeToStart, relativeTimeToStartError) =
          medianAndRelativeError(results.map(_.timeToStart.toMicros / 1000.0))
        val (timeToFirstElement, relativeTimeToFirstError) =
          medianAndRelativeError(results.map(_.timeToFirstElement.toMicros / 1000.0))
        f"${pad(name)}: $throughput%5.2fM elems/sec (± $relativeThroughputError%5.2f%%) with " +
          f"$timeToStart%4.2fms (± $relativeTimeToStartError%5.2f%%) to start and " +
          f"$timeToFirstElement%4.2fms (± $relativeTimeToFirstError%5.2f%%) to 1st elem"
      }

    val title = s"$suiteName BENCHMARK RESULTS"
    println(s"\n\n$title")
    println(pad("", title.length, '='))
    println()
    result.foreach(println)
    println()

    cleanUp()
  }

  def pad(name: String, to: Int = nameWidth, c: Char = ' '): String =
    if (name.length < to) {
      val sb = new java.lang.StringBuilder(to)
      sb.append(name)
      while (sb.length < to) sb.append(c)
      sb.toString
    } else name

  final def mio = 1000000L
  final def now = System.nanoTime()
  final def elapsedSince(timeStamp: Long): FiniteDuration = (now - timeStamp).nanos
}