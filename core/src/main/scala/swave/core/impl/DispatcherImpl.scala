/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.locks.LockSupport
import java.util.concurrent._
import org.slf4j.LoggerFactory
import scala.annotation.tailrec
import com.typesafe.scalalogging.Logger
import swave.core.Dispatcher.ThreadPoolConfig
import swave.core.Dispatcher.ThreadPoolConfig.ThreadPool.Prestart
import swave.core._

private[impl] final class DispatcherImpl(val settings: Dispatcher.Settings, create: () ⇒ ExecutorService)
    extends Dispatcher {
  import DispatcherImpl._

  private[this] val log   = Logger(LoggerFactory getLogger s"dispatcher-$name")
  private[this] val state = new AtomicReference[State]

  def name: String = settings.name

  @tailrec def execute(r: Runnable) =
    state.get match {
      case null ⇒
        if (state.compareAndSet(null, Creating)) {
          var es: ExecutorService = null
          try es = create()
          finally state.set(Running(es))
        }
        execute(r)

      case Running(es) ⇒ es.execute(r)

      case Terminated(_) ⇒
        throw new RejectedExecutionException(s"Dispatcher '$name' has already shut down")

      case Creating ⇒
        LockSupport.parkNanos(100 * 1000) // park for 100 microseconds
        execute(r)
    }

  /**
    * Triggers a shutdown and returns a function that allows for verification of the shutdown completion.
    */
  @tailrec def shutdown(): () ⇒ Boolean =
    state.get match {
      case null ⇒
        val result = () ⇒ true
        if (state.compareAndSet(null, Terminated(result))) result
        else shutdown()

      case x @ Running(es) ⇒
        val result = () ⇒ es.isTerminated
        if (state.compareAndSet(x, Terminated(result))) {
          es.shutdown()
          result
        } else shutdown()

      case Terminated(result) ⇒ result

      case Creating ⇒
        // Thread.onSpinWait() // TODO: enable once we are on JDK9
        shutdown()
    }

  def reportFailure(cause: Throwable): Unit =
    log.error("failure on dispatcher", cause)
}

private[core] object DispatcherImpl {
  import ThreadPoolConfig._

  private abstract class State
  private case object Creating                                       extends State
  private final case class Running(executorService: ExecutorService) extends State
  private final case class Terminated(result: () ⇒ Boolean)          extends State

  def apply(settings: Dispatcher.Settings): DispatcherImpl = {
    def scaledPoolSize(size: Size): Int =
      math.min(math.max((Runtime.getRuntime.availableProcessors * size.factor).ceil.toInt, size.min), size.max)

    val create: () ⇒ ExecutorService =
      settings match {
        case Dispatcher.Settings(name, ForkJoin(size, asyncMode, daemonic)) ⇒
          new AtomicLong with ForkJoinPool.ForkJoinWorkerThreadFactory with (() ⇒ ExecutorService) {
            def apply() = new ForkJoinPool(scaledPoolSize(size), this, null, asyncMode)
            def newThread(pool: ForkJoinPool): ForkJoinWorkerThread = {
              val thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool)
              thread.setDaemon(daemonic)
              thread.setName(s"swave-$name-${incrementAndGet()}")
              thread
            }
          }

        case Dispatcher
              .Settings(name, ThreadPool(corePoolSize, maxPoolSize, keepAlive, allowCoreTimeout, prestart, daemonic)) ⇒
          new AtomicLong with ThreadFactory with (() ⇒ ExecutorService) {
            def apply() = {
              val executor = new ThreadPoolExecutor(
                scaledPoolSize(corePoolSize),
                scaledPoolSize(maxPoolSize),
                keepAlive.toNanos,
                TimeUnit.NANOSECONDS,
                new LinkedBlockingQueue[Runnable],
                this)
              executor.allowCoreThreadTimeOut(allowCoreTimeout)
              prestart match {
                case Prestart.Off   ⇒ // default
                case Prestart.First ⇒ executor.prestartCoreThread()
                case Prestart.All   ⇒ executor.prestartAllCoreThreads()
              }
              executor
            }
            def newThread(r: Runnable) = {
              val thread = new Thread(r, s"swave-$name-${incrementAndGet()}")
              thread.setDaemon(daemonic)
              thread
            }
          }
      }
    new DispatcherImpl(settings, create)
  }
}
