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

import java.util.concurrent.CountDownLatch
import swave.core._
import swave.core.util.{DoubleCell, CellWheel, XorShiftRandom}
import scala.util.{Failure, Success}

object MonteCarloPi2 extends App {

  val random = XorShiftRandom()
  val cellWheel = new CellWheel(4, new DoubleCell)

//  println("Press ENTER to start!")
//  StdIn.readLine()

  implicit val env = StreamEnv()
  import env.defaultDispatcher
  val latch = new CountDownLatch(1)

  val r = Stream.continually(cellWheel.next().put(random.nextDouble()))
    .groupedToCellArray(2)
    .map(ca ⇒ Point(ca(0).extract(), ca(1).extract()))
    .fanOut()
      .sub.filter(_.isInner).map(_ => InnerSample).end
      .sub.filterNot(_.isInner).map(_ => OuterSample).end
    .fanInMerge()
    .scan(State(0, 0)) { _ withNextSample _ }
    .drop(1)
    .inject
    .map(_ drop 999999 take 1)
    .flattenConcat()
    .map(state ⇒ f"After ${state.totalSamples}%,10d samples π is approximated as ${state.π}%.5f")
    .take(20)
    .foreach(println)
    .onComplete {
      case Success(_) => latch.countDown()
      case Failure(e) =>
        e.printStackTrace()
        latch.countDown()
    }

  latch.await()
  println(f"Done. Total time: ${System.currentTimeMillis() - env.startTime}%,6d ms")

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
