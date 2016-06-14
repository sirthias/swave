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

package swave.testkit

import java.util.concurrent.{ LinkedBlockingQueue, TimeUnit }
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import swave.core.{ Drain, PipeElem }
import swave.core.impl.Inport
import swave.core.impl.stages.drain.DrainStage
import swave.core.macros.StageImpl
import swave.core.util._

object TestDrain {

  def probe[T](): Probe[T] = new Probe

  implicit def probe2Drain[T](p: Probe[T]): Drain[T, Unit] = new Drain(p.stage, ())

  final class Probe[T] private[TestDrain] {

    // format: OFF
    @StageImpl
    private[TestDrain] object stage extends DrainStage with PipeElem.Drain.Test {
      def pipeElemType = "TestDrain.Probe"
      def pipeElemParams = Nil

      val log = new LinkedBlockingQueue[Signal]
      def streamRunner = runner

      connectInAndSealWith { (ctx, in) ⇒
        ctx.registerForXStart(this)
        awaitingXStart(in, Queue.empty)
      }

      def awaitingXStart(in: Inport, commands: Queue[Signal]): State = state(
        xStart = () => {
          @tailrec def rec(remaining: Queue[Signal]): State =
            if (remaining.nonEmpty) {
              remaining.head match {
                case Signal.Request(n) => { in.request(n); rec(remaining.tail) }
                case Signal.Cancel => stopCancel(in)
                case _ => throw new IllegalStateException
              }
            } else running(in)
          rec(commands)
        },

        xEvent = { case x: Signal => awaitingXStart(in, commands enqueue x) })

      def running(in: Inport) = state(
        onNext = (elem, _) ⇒ {
          log.add(Signal.OnNext(elem))
          stay()
        },

        onComplete = _ => {
          log.add(Signal.OnComplete)
          stay()
        },

        onError = (e, _) ⇒ {
          log.add(Signal.OnError(e))
          stay()
        },

        xEvent = {
          case Signal.Request(n) => { in.request(n); stay() }
          case Signal.Cancel => stopCancel(in)
        })
    }
    // format: ON

    object send {
      def request(n: Long): Unit = runOrEnqueue(Signal.Request(n))
      def cancel(): Unit = runOrEnqueue(Signal.Cancel)

      private def runOrEnqueue(signal: Signal): Unit =
        if (stage.streamRunner eq null) { // sync
          stage.onNext(signal)(stage)
        } else { // async
          stage.streamRunner.scheduleEvent(stage, Duration.Zero, signal)
          ()
        }
    }

    object expect {
      def onNext(elem: Any): Within = new Within(Signal.OnNext(elem))
      def onComplete(): Within = new Within(Signal.OnComplete)
      def onError(e: Throwable): Within = new Within(Signal.OnError(e))

      final class Within(expected: Signal) {
        def now(): Unit = verify(stage.log.poll())
        def within(d: Duration)(implicit ec: ExecutionContext): Unit = {
          requireArg(stage.streamRunner ne null, "Expect within duration doesn't make sense for sync run")
          verify(stage.log.poll(d.toNanos, TimeUnit.NANOSECONDS))
        }
        private def verify(signal: Signal): Unit =
          signal match {
            case `expected` ⇒ // all good
            case received   ⇒ throw new ExpectationFailedException(received, expected)
          }
      }
    }
  }

  final class ExpectationFailedException(received: Any, expected: Any)
    extends RuntimeException("Test expectation failed: received " +
      (if (received != null) s"`$received`" else "nothing") + s" but expected `$expected`")
}
