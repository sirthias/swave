/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.testkit

import java.util.concurrent.{ TimeUnit, LinkedBlockingQueue }
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext
import swave.core.PipeElem
import swave.core.impl.Outport
import swave.core.impl.stages.source.SourceStage
import swave.core.Stream
import swave.core.macros._

// TODO: introduce default expect timeout read from config as well as dilation logic
object TestStream {

  def probe[T](): Probe[T] = new Probe

  implicit def probe2Stream[T](p: Probe[T]): Stream[T] = new Stream(p.stage)

  final class Probe[T] private[TestStream] {

    // format: OFF
    @StageImpl
    private[TestStream] object stage extends SourceStage with PipeElem.Source.Test {
      def pipeElemType = "TestStream.Probe"
      def pipeElemParams = Nil

      val log = new LinkedBlockingQueue[Signal]
      def streamRunner = runner

      connectOutAndSealWith { (ctx, out) ⇒
        ctx.registerForXStart(this)
        awaitingXStart(out, Queue.empty)
      }

      def awaitingXStart(out: Outport, commands: Queue[Signal]): State = state(
        xStart = () => {
          @tailrec def rec(remaining: Queue[Signal]): State =
            if (remaining.nonEmpty) {
              remaining.head match {
                case Signal.OnNext(elem) => { out.onNext(elem.asInstanceOf[AnyRef]); rec(remaining.tail) }
                case Signal.OnComplete => stopComplete(out)
                case Signal.OnError(e) => stopError(e, out)
                case _ => throw new IllegalStateException
              }
            } else running(out)
          rec(commands)
        },

        xEvent = { case x: Signal => awaitingXStart(out, commands enqueue x) })

      def running(out: Outport) = state(
        request = (n, _) ⇒ {
          log.add(Signal.Request(n.toLong))
          stay()
        },

        cancel = _ => {
          log.add(Signal.Cancel)
          stay()
        },

        xEvent = {
          case Signal.OnNext(elem) => { out.onNext(elem.asInstanceOf[AnyRef]); stay() }
          case Signal.OnComplete => stopComplete(out)
          case Signal.OnError(e) => stopError(e, out)
        })
    }
    // format: ON

    object send {
      def onNext(values: Any*): Unit = runOrEnqueue(values.map(Signal.OnNext): _*)
      def onComplete(): Unit = runOrEnqueue(Signal.OnComplete)
      def onError(e: Throwable): Unit = runOrEnqueue(Signal.OnError(e))

      private def runOrEnqueue(signals: Signal*): Unit =
        signals.foreach {
          if (stage.streamRunner eq null) { // sync
            stage.onNext(_)(stage)
          } else { // async
            stage.streamRunner.scheduleEvent(stage, Duration.Zero, _)
          }
        }
    }

    object expect {
      def request(n: Long): Within = new Within(Signal.Request(n))
      def cancel(): Within = new Within(Signal.Cancel)

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
