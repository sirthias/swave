/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.testkit

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ TimeUnit, LinkedBlockingQueue }
import scala.annotation.tailrec
import scala.util.DynamicVariable
import scala.collection.immutable
import scala.concurrent.{ TimeoutException, Await, Future, Promise }
import scala.concurrent.duration._
import swave.testkit.impl.TestkitExtension
import swave.core.impl.stages.Stage
import swave.core.impl.stages.drain.DrainStage
import swave.core.impl.{ Inport, Outport }
import swave.core.impl.stages.source.SourceStage
import swave.core.macros._
import swave.core.util._
import swave.core._
import Testkit._

trait Probes {

  private val deadlineNanos = new DynamicVariable(0L)

  def within[T](max: FiniteDuration)(block: ⇒ T)(implicit env: StreamEnv): T =
    deadlineNanos.withValue(System.nanoTime() + max.dilated.toNanos)(block)

  object StreamProbe {
    def apply[T](implicit env: StreamEnv): StreamProbe[T] = new StreamProbe(TestkitExtension(env))
    implicit def probe2Stream[T](p: StreamProbe[T]): Stream[T] = new Stream(p.stage)
  }

  final class StreamProbe[T] private (_ext: TestkitExtension)(implicit env: StreamEnv) extends Probe(_ext) {
    private[this] val _totalDemand = new AtomicLong

    def totalDemand: Long = _totalDemand.get

    // format: OFF
    @StageImpl
    protected object stage extends SourceStage with PipeElem.Source.Test with ProbeStage {
      def pipeElemType = "StreamProbe"
      def pipeElemParams = Nil
      override def toString = s"StreamProbe.stage@${identityHash(this)}/$stateName"

      connectOutAndSealWith { (ctx, out) ⇒
        ctx.registerForXStart(this)
        awaitingXStart(out, immutable.Queue.empty)
      }

      def awaitingXStart(out: Outport, commands: immutable.Queue[Signal]): State = state(
        xStart = () => {
          @tailrec def rec(remaining: immutable.Queue[Signal], elems: immutable.Queue[T]): State =
            if (remaining.nonEmpty) {
              remaining.head match {
                case Signal.OnNext(elem) => rec(remaining.tail, elems.enqueue(elem.asInstanceOf[T]))
                case Signal.OnComplete => stopComplete(out)
                case Signal.OnError(e) => stopError(e, out)
                case _ => throw new IllegalStateException
              }
            } else running(out, elems)
          rec(commands, immutable.Queue.empty)
        },

        xEvent = { case x: Signal => awaitingXStart(out, commands enqueue x) })

      def running(out: Outport, backlog: immutable.Queue[T]): State = state(
        request = (n, _) ⇒ {
          @tailrec def rec(demand: Long, elems: immutable.Queue[T]): State =
            if (backlog.nonEmpty && demand > 0) {
              out.onNext(elems.head.asInstanceOf[AnyRef])
              rec(demand - 1, elems.tail)
            } else {
              _totalDemand.set(demand)
              running(out, elems)
            }
          log.add(Signal.Request(n.toLong))
          rec(_totalDemand.addAndGet(n.toLong), backlog)
        },

        cancel = _ => {
          log.add(Signal.Cancel)
          stop()
        },

        xEvent = {
          case Signal.OnNext(elem) =>
            if (totalDemand > 0) {
              requireState(backlog.isEmpty)
              _totalDemand.decrementAndGet()
              out.onNext(elem.asInstanceOf[AnyRef])
              stay()
            } else running(out, backlog.enqueue(elem.asInstanceOf[T]))

          case Signal.OnComplete => stopComplete(out)
          case Signal.OnError(e) => stopError(e, out)
        })
    }
    // format: ON

    def sendNext(first: T, more: T*): Unit = sendNext(first +: more)
    def sendNext(values: Seq[T]): Unit = runOrEnqueue(values.map(Signal.OnNext): _*)
    def sendComplete(): Unit = runOrEnqueue(Signal.OnComplete)
    def sendError(e: Throwable): Unit = runOrEnqueue(Signal.OnError(e))

    def expectRequest(): Long = expect { case Signal.Request(n) ⇒ n }
    def expectRequest(n: Long): Unit = expectSignal(Signal.Request(n))

    /**
     * Aggregates all request signals arriving within the given time period.
     * Returns early (with the aggregated value) if a non-request signal is received.
     */
    def expectRequestAggregated(max: FiniteDuration): Long = {
      @tailrec def rec(acc: Long): Long =
        peekSignal() match {
          case Some(Signal.Request(_)) ⇒ rec(acc + expectRequest())
          case _                       ⇒ acc
        }
      within(max)(rec(0L))
    }

    def expectCancel(): Unit = expectSignal(Signal.Cancel)
  }

  object DrainProbe {
    def apply[T](implicit env: StreamEnv): DrainProbe[T] = new DrainProbe(TestkitExtension(env))
    implicit def probe2Drain[T](p: DrainProbe[T]): Drain[T, DrainProbe[T]] = new Drain(p.stage, p)
  }

  final class DrainProbe[T] private (_ext: TestkitExtension)(implicit env: StreamEnv) extends Probe(_ext) {

    // format: OFF
    @StageImpl
    protected object stage extends DrainStage with PipeElem.Drain.Test with ProbeStage {
      def pipeElemType = "DrainProbe"
      def pipeElemParams = Nil
      override def toString = s"DrainProbe.stage@${identityHash(this)}/$stateName"

      connectInAndSealWith { (ctx, in) ⇒
        ctx.registerForXStart(this)
        awaitingXStart(in, immutable.Queue.empty)
      }

      def awaitingXStart(in: Inport, commands: immutable.Queue[Signal]): State = state(
        xStart = () => {
          @tailrec def rec(remaining: immutable.Queue[Signal]): State =
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
          stop()
        },

        onError = (e, _) ⇒ {
          log.add(Signal.OnError(e))
          stop(e)
        },

        xEvent = {
          case Signal.Request(n) => { in.request(n); stay() }
          case Signal.Cancel => stopCancel(in)
        })
    }
    // format: ON

    def sendRequest(n: Long): this.type = { runOrEnqueue(Signal.Request(n)); this }
    def sendCancel(): this.type = { runOrEnqueue(Signal.Cancel); this }

    def expectNext(): Any = expect { case Signal.OnNext(x) ⇒ x }
    def expectNext(elems: T*): this.type = { elems.foreach(x ⇒ expectSignal(Signal.OnNext(x))); this }
    def expectComplete(): this.type = { expectSignal(Signal.OnComplete); this }
    def expectError(): Throwable = expect { case Signal.OnError(x) ⇒ x }
    def expectError(e: Throwable): this.type = { expectSignal(Signal.OnError(e)); this }

    def receiveElementsWithin(time: FiniteDuration, maxElems: Int = Int.MaxValue): List[T] =
      receiveSignalsWhile(maxTotalTime = time, maxSignals = maxElems) { case Signal.OnNext(elem) ⇒ elem.asInstanceOf[T] }
  }

  abstract class Probe(ext: TestkitExtension)(implicit env: StreamEnv) {
    private[this] val singleExpectDefaultDilatedNanos =
      ext.settings.timingDefaults.singleExpectDefault.dilated.toNanos

    private[this] var nextSignal: Option[Signal] = None

    final def expectSignal(): Option[Signal] =
      if (nextSignal.isEmpty) Option {
        if (stage.isSync) {
          requireState(deadlineNanos.value == 0L, "Expect `within` duration doesn't make sense for sync runs")
          stage.log.poll()
        } else stage.log.poll(withinTimeoutNanos, TimeUnit.NANOSECONDS)
      }
      else {
        val result = nextSignal
        nextSignal = None
        result
      }

    final def expectSignal(expected: Signal): Unit = expectSignal(Some(expected))
    final def expectSignal(expected: Option[Signal]): Unit = verifyEquals(expectSignal(), expected)

    final def expect[T](pf: PartialFunction[Signal, T]): T =
      expectSignal() match {
        case Some(signal) if pf isDefinedAt signal ⇒ pf(signal)
        case x                                     ⇒ throw ExpectationFailedException(x, Some("a value matching the given partial function"))
      }

    final def expectNoSignal(): this.type = { verifyEquals(peekSignal(), None); this }
    final def expectNoSignal(max: FiniteDuration): this.type = { within(max)(expectSignal(None)); this }

    final def verifyCleanStop(): Unit =
      if (stage.isAsync) {
        val futures: List[(Stage, Future[Unit])] = Stage.discoverGraph(stage.stage).map { stage ⇒
          val p = Promise[Unit]()
          stage.runner.enqueueXEvent(stage, Stage.RegisterStopPromise(p))
          stage → p.future
        }(collection.breakOut)
        import env.defaultDispatcher
        try {
          Await.ready(Future.sequence(futures.map(_._2)), withinTimeoutNanos.nanos)
          ()
        } catch {
          case _: TimeoutException ⇒
            val unstoppedStages = futures.collect { case (stage, future) if !future.isCompleted ⇒ stage }
            throw ExpectationFailedException(
              unstoppedStages.mkString("At least one stage is still running when it shouldn't be:\n    ", "\n    ", "\n"))
        }
      } else {
        requireState(deadlineNanos.value == 0L, "`verifyCleanStop` wrapped by `within` doesn't make sense for sync runs")
        Stage.discoverGraph(stage.stage).find(!_.isStopped).foreach(stage ⇒
          throw ExpectationFailedException(s"Stage `$stage` is still running when it shouldn't be."))
      }

    /**
     * Receives a series of signals until any one of these conditions becomes true:
     * - the received signal isn't matched by the given partial function
     * - the `maxTotalTime` is elapsed
     * - the `maxIdleTime` (max. time between two messages) expires
     * - the `maxSignals` count is reached
     *
     * Returns the results of the partial function for all successfully received signals.
     */
    final def receiveSignalsWhile[A](maxTotalTime: FiniteDuration = 1.day, maxIdleTime: FiniteDuration = 1.day,
      maxSignals: Int = Int.MaxValue)(pf: PartialFunction[Signal, A]): List[A] = {
      val deadlineNanos = System.nanoTime() + maxTotalTime.dilated.toNanos
      val maxIdleTimeDilated = maxIdleTime.dilated

      @tailrec def rec(maxRemaining: Int, acc: List[A]): List[A] =
        if (maxRemaining > 0) {
          val wait = (deadlineNanos - System.nanoTime()).nanos min maxIdleTimeDilated
          if (wait > Duration.Zero) {
            val recurse =
              within(wait) {
                val optSignal = peekSignal()
                optSignal.isDefined && pf.isDefinedAt(optSignal.get)
              }
            if (recurse) rec(maxRemaining - 1, pf(expectSignal().get) :: acc) else acc.reverse
          } else acc.reverse
        } else acc.reverse
      rec(maxSignals, Nil)
    }

    protected trait ProbeStage { this: Stage ⇒
      val log = new LinkedBlockingQueue[Signal]
      def streamRunner = runner
      def onSignal(s: Signal): Unit = xEvent(s)
      def stage: Stage = this
      def isSync = streamRunner eq null
      def isAsync = streamRunner ne null
    }

    protected def stage: ProbeStage

    protected final def runOrEnqueue(signals: Signal*): Unit =
      signals.foreach {
        if (stage.isSync) stage.onSignal
        else stage.streamRunner.scheduleEvent(stage.stage, Duration.Zero, _)
      }

    protected final def peekSignal(): Option[Signal] = {
      nextSignal = expectSignal()
      nextSignal
    }

    private def verifyEquals(received: Option[Signal], expected: Option[Any]): Unit =
      if (received != expected) throw ExpectationFailedException(received, expected)

    private def withinTimeoutNanos: Long =
      if (deadlineNanos.value == 0L) singleExpectDefaultDilatedNanos
      else deadlineNanos.value - System.nanoTime()
  }
}

object Probes extends Probes
