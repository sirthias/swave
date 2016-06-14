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

package swave.core.impl

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import org.jctools.queues.MpscLinkedQueue8
import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.concurrent.duration._
import scala.concurrent.{ Promise, Future, ExecutionContext }
import swave.core.internal.agrona.TimerWheel
import swave.core.util._
import swave.core._

/**
 * Initial scheduler implementation with quite some improvement potential left.
 * Hoping that https://github.com/JCTools/JCTools/issues/109 will spare us from having to implement
 * a better alternative outselves.
 */
private[core] final class SchedulerImpl private (val settings: Scheduler.Settings) extends Scheduler {
  import SchedulerImpl._

  private[this] val wheel = new TimerWheel(settings.tickDuration.toNanos, settings.ticksPerWheel)
  private[this] val cancelledTimer = new wheel.Timer()
  private[this] val newTasks = new MpscLinkedQueue8[Task]
  private[this] val cancellations = new MpscLinkedQueue8[wheel.Timer]
  private[this] val state = new AtomicReference[State](UnstartedState)

  def schedule(initialDelay: FiniteDuration, interval: FiniteDuration, r: Runnable)(implicit ec: ExecutionContext): Cancellable = {
    requireArg(initialDelay >= Duration.Zero)
    requireArg(interval > Duration.Zero)
    schedule(initialDelay.toNanos, interval.toNanos, r)
  }

  def scheduleOnce(delay: FiniteDuration, r: Runnable)(implicit ec: ExecutionContext): Cancellable = {
    requireArg(delay >= Duration.Zero)
    schedule(delay.toNanos, 0, r)
  }

  @tailrec def shutdown(): Future[Unit] =
    state.get match {
      case UnstartedState ⇒
        val promise = Promise.successful(())
        if (state.compareAndSet(UnstartedState, ShutdownState(promise))) promise.future else shutdown()

      case ActiveState ⇒
        val promise = Promise[Unit]()
        if (state.compareAndSet(ActiveState, ShutdownState(promise))) promise.future else shutdown()

      case ShutdownState(promise) ⇒ promise.future
    }

  @tailrec
  private def schedule(delayNanos: Long, intervalNanos: Long, r: Runnable)(implicit ec: ExecutionContext): Cancellable =
    state.get match {
      case ActiveState ⇒
        val deadline = wheel.ticks + delayNanos
        requireArg(deadline > 0, "delay is too large") // otherwise we had a Long overflow
        val task = new Task(deadline, intervalNanos, r)
        newTasks.add(task)
        task

      case UnstartedState ⇒
        if (state.compareAndSet(UnstartedState, ActiveState))
          new TimerThread().start()
        schedule(delayNanos, intervalNanos, r)

      case ShutdownState(_) ⇒
        throw new RejectedExecutionException("Cannot accept scheduling after Scheduler shutdown")
    }

  private final class Task(val deadline: Long, intervalNanos: Long, runnable: Runnable)(implicit ec: ExecutionContext)
      extends AtomicReference[wheel.Timer] with Runnable with Cancellable {

    // executed on the TimerThread!
    def run(): Unit = {
      ec.execute(runnable)
      if (intervalNanos != 0) {
        get match {
          case null  ⇒ addToWheel(wheel.ticks() + intervalNanos)
          case timer ⇒ wheel.rescheduleTimeout(intervalNanos, timer)
        }
      }
    }

    // executed on the TimerThread!
    def addToWheel(deadline: Long): Unit = {
      val timer = wheel.newTimeout(deadline, this).asInstanceOf[wheel.Timer]
      if (!compareAndSet(null, timer))
        timer.cancel() // task was cancelled before we were able to import it
    }

    def cancel() =
      getAndSet(cancelledTimer) match {
        case null  ⇒ true // we've cancelled before the import on the TimerThread
        case timer ⇒ timer.isActive && cancellations.add(timer)
      }

    def stillActive = get.isActive
  }

  private final class TimerThread extends Thread {
    private[this] val log = Logger(LoggerFactory getLogger "swave.core.Scheduler")

    setName("timer-thread")
    setDaemon(true)

    override def run(): Unit =
      try {
        log.debug("TimerThread '{}' started", Thread.currentThread.getName)

        @tailrec def loop(): Promise[Unit] =
          state.get match {
            case ActiveState ⇒
              processCancellations()
              val count = wheel.expireTimers()
              log.trace("{} timers expired", count)
              if (state.get == ActiveState) {
                importNewTasks()
                val millis = wheel.computeDelayInMs()
                if (millis > 0) Thread.sleep(millis)
              }
              loop()
            case ShutdownState(promise) ⇒ promise
            case UnstartedState         ⇒ throw new IllegalStateException
          }

        val terminationPromise = loop()
        log.debug("TimerThread '{}' stopped", Thread.currentThread.getName)
        terminationPromise.success(())
        ()
      } catch {
        case NonFatal(e) ⇒
          log.error("TimerThread crash", e)
          state.compareAndSet(ActiveState, ShutdownState(Promise[Unit]()))
          state.get.asInstanceOf[ShutdownState].promise.tryFailure(e)
          ()
      }

    @tailrec private def processCancellations(): Unit =
      cancellations.poll() match {
        case null ⇒ // done
        case timer ⇒
          timer.cancel()
          processCancellations()
      }

    @tailrec private def importNewTasks(): Unit =
      newTasks.poll() match {
        case null ⇒ // done
        case task if task.deadline <= wheel.ticks ⇒
          task.run()
          importNewTasks()
        case task ⇒
          task.addToWheel(task.deadline)
          importNewTasks()
      }
  }
}

private[core] object SchedulerImpl {
  def apply(settings: Scheduler.Settings): SchedulerImpl = new SchedulerImpl(settings)

  private abstract class State
  private case object UnstartedState extends State
  private case object ActiveState extends State
  private final case class ShutdownState(promise: Promise[Unit]) extends State
}