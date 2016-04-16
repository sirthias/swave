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

package swave.testkit.impl

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import swave.testkit.TestSetup.AsyncScheduling
import swave.core.util._

private[testkit] final class TestContext(
    val runNr: Int,
    val asyncRate: Double,
    val asyncScheduling: AsyncScheduling,
    random: XorShiftRandom,
    tracing: Boolean) {

  import TestContext._

  private[this] val schedulings = ArrayBuffer.empty[ResizableRingBuffer[Task]]

  def lastId = schedulings.size - 1

  def nextId(): Int = {
    schedulings += new ResizableRingBuffer[Task](16, 4096)
    schedulings.size - 1
  }

  def trace(msg: ⇒ String)(implicit stage: TestStage): Unit =
    if (tracing) println(stage.toString + ": " + msg)

  def run(msg: ⇒ String)(block: ⇒ Unit)(implicit stage: TestStage): Unit = {
    val scheduled = schedulings(stage.id)
    if (scheduled.nonEmpty || random.decide(asyncRate)) {
      trace("(scheduling) " + msg)
      requireState(scheduled.write(new Task(stage, msg _, block _)))
    } else {
      trace("(sync)       " + msg)
      block
    }
  }

  def hasSchedulings: Boolean = schedulings.exists(_.nonEmpty)

  @tailrec def processSchedulings(): Unit =
    if (hasSchedulings) {
      val snapshot: Array[ResizableRingBuffer[Task]] = schedulings.toArray

      def runSnapshots() = snapshot foreach { buf ⇒ runTasks(buf, buf.size) }

      @tailrec def runTasks(buf: ResizableRingBuffer[Task], count: Int): Unit =
        if (count > 0) {
          val task = buf.read()
          trace("(running)    " + task.msg())(task.stage)
          task.block()
          runTasks(buf, count - 1)
        }

      asyncScheduling match {
        case AsyncScheduling.InOrder ⇒
          runSnapshots()

        case AsyncScheduling.RandomOrder ⇒
          random.shuffle_!(snapshot)
          runSnapshots()

        case AsyncScheduling.ReversedOrder ⇒
          snapshot.reverse_!()
          runSnapshots()

        case AsyncScheduling.Mixed ⇒
          @tailrec def rec(remaining: Array[ResizableRingBuffer[Task]]): Unit =
            if (remaining.nonEmpty) {
              random.shuffle_!(remaining)
              rec(remaining flatMap { buf ⇒
                val jobsSize = buf.size
                runTasks(buf, random.nextInt(jobsSize + 1)) // at least one, at most all
                if (buf.nonEmpty) buf :: Nil else Nil
              })
            }
          rec(snapshot)
      }
      processSchedulings()
    }
}

private[testkit] object TestContext {

  private class Task(val stage: TestStage, val msg: () ⇒ String, val block: () ⇒ Unit)
}