/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import swave.core.impl.stages.StageImpl
import swave.core.macros._
import swave.core.util._
import swave.core._

/**
  * A `RunContext` instance keeps all the contextual information required for the start of a *single* connected stream
  * network. This does not include embedded or enclosing sub-streams (streams of streams).
  */
private[swave] final class RunContext(val stage: StageImpl)(implicit val env: StreamEnv) {
  import RunContext._

  private[this] var runnerAssignments = List.empty[StreamRunner.Assignment]
  private[this] var needXStart        = List.empty[StageImpl]
  private var _global: Global         = _

  def global: Global = _global

  def asyncGlobal: AsyncGlobal =
    _global match {
      case null           => { val x = new AsyncGlobal(this); _global = x; x }
      case x: AsyncGlobal => x
      case _              => throw new IllegalStateException
    }

  def syncGlobal: SyncGlobal =
    _global match {
      case null          => { val x = new SyncGlobal(this); _global = x; x }
      case x: SyncGlobal => x
      case _             => throw new IllegalStateException
    }

  //////////////////////////////// CALLED DURING SEALING ///////////////////////////////////////////
  // therefore allowed to mutate, after starting no mutation is allowed anymore if the run is async!

  /**
    * Seals the stream by sending an `xSeal` signal through the stream graph starting from `port`.
    */
  def seal(): Unit = {
    if (stage.isSealed) {
      val msg = stage + " is already sealed. It cannot be sealed a second time. " +
          "Are you trying to reuse a Spout, Drain, Pipe or Module?"
      throw new IllegalReuseException(msg)
    }
    stage.xSeal(this)
    if (runnerAssignments ne Nil) {
      if (global.isInstanceOf[SyncGlobal]) {
        throw new IllegalAsyncBoundaryException(
          "A synchronous parent stream must not contain " +
            "an async sub-stream. You can fix this by explicitly marking the parent stream as `async`.")
      }
      asyncGlobal.assignRunners(runnerAssignments)
    }
  }

  private[impl] def linkToParentContext(parent: RunContext): Unit = {
    requireState(parent.env eq env, "All sub streams of a stream run must use the same `StreamEnv` instance")
    if (!parent.stage.hasRunner) {
      parent.syncGlobal.merge(syncGlobal)
    }
    _global = parent.global
  }

  /**
    * Registers a stage for assignment of a [[StreamRunner]] with the given `dispatcherId`.
    * If the `dispatcherId` is empty the default dispatcher will be assigned
    * if no other non-default assignment has previously been made.
    */
  def registerRunnerAssignment(assignment: StreamRunner.Assignment): Unit =
    runnerAssignments ::= assignment

  /**
    * Registers the stage for receiving `xStart` signals.
    */
  def registerForXStart(s: StageImpl): Unit =
    needXStart ::= s

  /**
    * Starts (and potentially runs) the stream.
    */
  def start(): Unit = {
    @tailrec def startSync(remaining: List[StageImpl]): Unit =
      if (remaining ne Nil) {
        remaining.head.xStart()
        startSync(remaining.tail)
      }
    @tailrec def startAsync(remaining: List[StageImpl]): Unit =
      if (remaining ne Nil) {
        remaining.head.runner.enqueueXStart(remaining.head)
        startAsync(remaining.tail)
      }

    if (!stage.hasRunner) {
      startSync(needXStart)
      syncGlobal.postRun(this)
    } else startAsync(needXStart)
  }

  //////////////////////////////// CALLED AFTER START ///////////////////////////////////////////
  // No mutation is allowed anymore if the run is async!!!

  def scheduleSubscriptionTimeout(s: StageImpl): Cancellable =
    env.settings.subscriptionTimeout match {
      case d: FiniteDuration ⇒ _global.scheduleSubscriptionTimeout(s, d)
      case _                 ⇒ Cancellable.Inactive
    }

  /**
    * Seals and starts the given port with the same [[StreamEnv]] as this RunContext.
    *
    * CAUTION: This method will throw if there is any problem with sealing or starting the sub stream.
    */
  private[impl] def sealAndStartSubStream(stage: StageImpl): Unit = {
    val subCtx = new RunContext(stage)
    subCtx.seal()
    subCtx.start()
  }
}

private[swave] object RunContext {
  case object PostRun
  case object SubscriptionTimeout

  // keeps the state for the global run, i.e. across all potentially nested sub streams
  private[swave] sealed abstract class Global(protected val outermostRunContext: RunContext) {
    def scheduleSubscriptionTimeout(stage: StageImpl, duration: FiniteDuration): Cancellable
  }

  private[swave] final class AsyncGlobal(_outermost: RunContext)(implicit val env: StreamEnv)
      extends Global(_outermost) {
    val termination: Promise[Unit] = Promise[Unit]()
    private[this] val runners      = new AtomicReference(Set.empty[StreamRunner])

    def assignRunners(assignments: List[StreamRunner.Assignment]): Unit = {
      @tailrec def register(list: List[StreamRunner]): Unit = {
        @tailrec def add(remaining: List[StreamRunner], result: Set[StreamRunner]): Set[StreamRunner] =
          if (remaining ne Nil) add(remaining.tail, result + remaining.head) else result

        val current = runners.get()
        val updated = add(list, current)
        if (!runners.compareAndSet(current, updated)) {
          register(list) // another thread interfered, so we try again
        }
      }

      register(StreamRunner.assignRunners(assignments, this))
    }

    @tailrec def unregisterRunner(runner: StreamRunner): Unit = {
      val current = runners.get()
      val updated = current - runner
      if (runners.compareAndSet(current, updated)) {
        if (updated.isEmpty) { termination.success(()); () }
      } else unregisterRunner(runner) // another thread interfered, so we try again
    }

    def scheduleSubscriptionTimeout(stage: StageImpl, duration: FiniteDuration): Cancellable =
      stage.runner.scheduleEvent(stage, duration, SubscriptionTimeout)
  }

  private[swave] final class SyncGlobal(_outermost: RunContext)(implicit val env: StreamEnv)
      extends Global(_outermost) {
    private var needPostRun = List.empty[StageImpl]
    private var cleanUp     = List.empty[Runnable]

    /**
      * Registers the stage for receiving `xEvent(RunContext.PostRun)` signals.
      * This method is also available from within the `xEvent` event handler,
      * which can re-register its stage to receive this event once more.
      */
    def registerForPostRunEvent(stage: StageImpl): Unit = needPostRun ::= stage

    def scheduleSubscriptionTimeout(stage: StageImpl, duration: FiniteDuration): Cancellable = {
      val timer =
        new Cancellable with Runnable {
          def run(): Unit          = stage.xEvent(SubscriptionTimeout)
          def stillActive: Boolean = cleanUp contains this
          def cancel(): Boolean = {
            val x = cleanUp.remove(this)
            (x ne cleanUp) && { cleanUp = x; true }
          }
        }
      cleanUp ::= timer
      timer
    }

    private[RunContext] def merge(other: SyncGlobal): Unit = {
      needPostRun :::= other.needPostRun
      cleanUp :::= other.cleanUp
    }

    private[RunContext] def postRun(runCtx: RunContext): Unit =
      if (runCtx eq outermostRunContext) {

        @tailrec def dispatchPostRunSignal(): Unit =
          if (needPostRun ne Nil) {
            val stages = needPostRun
            needPostRun = Nil // allow for re-registration in signal handler

            @tailrec def rec(remaining: List[StageImpl]): Unit =
              if (remaining ne Nil) {
                remaining.head.xEvent(PostRun)
                rec(remaining.tail)
              }
            rec(stages)
            dispatchPostRunSignal()
          }

        @tailrec def runCleanups(remaining: List[Runnable]): Unit =
          if (remaining ne Nil) {
            remaining.head.run()
            runCleanups(remaining.tail)
          }

        dispatchPostRunSignal()
        runCleanups(cleanUp)
        // if (!runCtx.stage.isStopped) throw new UnterminatedSynchronousStreamException
      }
  }
}
