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

import scala.util.{Failure, Success}
import swave.core._
import swave.core.util.XorShiftRandom

object MonteCarloPiAsync extends App {

  implicit val env = StreamEnv()
  import env.defaultDispatcher

  val random = XorShiftRandom()

  Stream.continually(random.nextDouble())
    .grouped(2)
    .map { case x +: y +: Nil ⇒ Point(x, y) }
    .fanOutBroadcast()
      .sub.filter(_.isInner).map(_ => InnerSample).end
      .sub.filterNot(_.isInner).map(_ => OuterSample).end
    .fanInMerge()
    .async()
    .scan(State(0, 0)) { _ withNextSample _ }
    .drop(1)
    .inject()
    .map(_ drop 999999 take 1)
    .flattenConcat()
    .map(state ⇒ f"After ${state.totalSamples}%,10d samples π is approximated as ${state.π}%.6f")
    .take(30)
    .foreach(println)
    .onComplete {
      case Success(_) =>
        val time = System.currentTimeMillis() - env.startTime
        println(f"Done. Total time: $time%,6d ms, Throughput: ${30000.0/time}%.3fM samples/sec\n")
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
}
