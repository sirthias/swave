/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

object MonteCarloPiThrottled extends App {
  //#example
  import scala.util.{Failure, Success}
  import scala.concurrent.duration._
  import swave.core.util.XorShiftRandom
  import swave.core._

  implicit val env = StreamEnv()
  import env.defaultDispatcher // for the future transformations below

  val random = XorShiftRandom()

  Spout.continually(random.nextDouble())
    .grouped(2)
    .map { case x +: y +: Nil => Point(x, y) }
    .fanOutBroadcast()
      .sub.filter(_.isInner).map(_ => InnerSample).end
      .sub.filterNot(_.isInner).map(_ => OuterSample).end
    .fanInMerge()
    .scan(State(0, 0)) { _ withNextSample _ }
    .conflateToLast
    .throttle(1, per = 1.second, burst = 0)
    .onElement(state =>
      println(f"After ${state.totalSamples}%,10d samples π is approximated as ${state.π}%.6f"))
    .take(20)
    .drainToLast()
    .onComplete {
      case Success(State(totalSamples, _)) =>
        val time = System.currentTimeMillis() - env.startTime
        val throughput = totalSamples / 1000.0 / time
        println(f"Done. Total time: $time%,6d ms, Throughput: $throughput%.3fM samples/sec\n")
        env.shutdown()

      case Failure(e) => println(e)
    }

  println("Main thread exit.")

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
  //#example
}