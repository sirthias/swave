/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.FiniteDuration
import swave.core.impl.stages.StageImpl
import swave.core.macros._
import swave.core.util._
import swave.core._

private[swave] object RunSupport {

  /**
    * Seals the stream by sending an `xSeal` signal through the stream graph starting from the given stage.
    */
  def seal(stage: StageImpl, sctx: SealingContext): RunContext = {
    verifyUnsealed(stage)
    stage.xSeal(sctx)
    val rctx =
      if (sctx.isSubStream) {
        if (sctx.isAsync && !sctx.runContext.isAsync) {
          throw new IllegalAsyncBoundaryException(
            "A synchronous parent stream must not contain an async sub-stream. " +
              "You can fix this by explicitly marking the parent stream as `async`.")
        }
        sctx.runContext
      } else new RunContext(sctx.isAsync, sctx.env)
    rctx.errorOnSyncUnstopped &&= sctx.errorOnSyncUnstopped
    assignRunContext(sctx.needRunContext, rctx)
    if (sctx.isAsync) rctx.assignRunners(sctx.runnerAssignments)
    rctx
  }

  def start(stage: StageImpl, sctx: SealingContext, rctx: RunContext): Unit =
    if (!rctx.isAsync) {
      syncStart(sctx.needXStart)
      if (sctx.isMainStream) {
        rctx.syncPostRun()
        if (rctx.errorOnSyncUnstopped && !stage.isStopped) throw new UnterminatedSynchronousStreamException
      }
    } else asyncStart(sctx.needXStart)

  def sealAndStartSubStream(stage: StageImpl, rctx: RunContext): Unit = {
    val sctx = new SealingContext(rctx.env)
    val rc   = seal(stage, sctx)
    requireState(rc eq rctx)
    start(stage, sctx, rctx)
  }

  private def verifyUnsealed(stage: StageImpl): Unit =
    if (stage.isSealed) {
      val msg = stage + " is already sealed. It cannot be sealed a second time. " +
          "Are you trying to reuse a Spout, Drain, Pipe or Module?"
      throw new IllegalReuseException(msg)
    }

  @tailrec private def assignRunContext(remaining: List[RunContextAccess], ctx: RunContext): Unit =
    if (remaining ne Nil) {
      remaining.head._runContext = ctx
      assignRunContext(remaining.tail, ctx)
    }

  @tailrec private def asyncStart(remaining: List[StageImpl]): Unit =
    if (remaining ne Nil) {
      remaining.head.runner.enqueueXStart(remaining.head)
      asyncStart(remaining.tail)
    }

  @tailrec private def syncStart(remaining: List[StageImpl]): Unit =
    if (remaining ne Nil) {
      remaining.head.xStart()
      syncStart(remaining.tail)
    }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // Context object for a single sealing phase of the main stream or any sub stream of a single stream run.
  private[swave] final class SealingContext(val env: StreamEnv) {
    private[RunSupport] var runnerAssignments      = List.empty[StreamRunner.Assignment]
    private[RunSupport] var needXStart             = List.empty[StageImpl]
    private[RunSupport] var needRunContext         = List.empty[RunContextAccess]
    private[RunSupport] var errorOnSyncUnstopped   = true
    private[RunSupport] var runContext: RunContext = _

    def registerRunnerAssignment(assignment: StreamRunner.Assignment): Unit = runnerAssignments ::= assignment
    def registerForXStart(stage: StageImpl): Unit                           = needXStart ::= stage
    def registerForRunContextAccess(stage: RunContextAccess): Unit          = needRunContext ::= stage
    def disableErrorOnSyncUnstopped(): Unit                                 = errorOnSyncUnstopped = false

    private[impl] def assignRunContext(ctx: RunContext): Unit = {
      if (ctx.env ne env)
        throw new IllegalStreamSetupException("All sub streams of a stream run must use the same `StreamEnv` instance")
      if ((runContext ne null) && (ctx ne runContext))
        throw new IllegalStreamSetupException(
          s"A sub-stream cannot have more than one parent stream! (ctx=$ctx, runContext=$runContext")
      runContext = ctx
    }

    // only valid *after* sealing!
    private[core] def isAsync: Boolean      = runnerAssignments ne Nil
    private[core] def isSubStream: Boolean  = runContext ne null
    private[core] def isMainStream: Boolean = runContext eq null
  }

  private[swave] trait RunContextAccess {
    private[RunSupport] var _runContext: RunContext = _
    def runContext: RunContext                      = _runContext
  }

  // Context object for a single stream run, including potentially many sub-stream runs.
  private[swave] final class RunContext(val isAsync: Boolean, val env: StreamEnv) {
    private[this] val termination: Promise[Unit] =
      if (isAsync) Promise[Unit]() else null
    private[this] val runners: AtomicReference[Set[StreamRunner]] =
      if (isAsync) new AtomicReference(Set.empty[StreamRunner]) else null
    private[this] var syncNeedPostRun                     = List.empty[StageImpl]
    private[this] var syncCleanUp                         = List.empty[Runnable]
    private[RunSupport] var errorOnSyncUnstopped: Boolean = true

    def terminationFuture: Future[Unit] =
      if (isAsync) termination.future else Future.successful(())

    def scheduleSubscriptionTimeout(s: StageImpl): Cancellable =
      env.settings.subscriptionTimeout match {
        case d: FiniteDuration if isAsync ⇒ s.runner.scheduleEvent(s, d, SubscriptionTimeout)
        case d: FiniteDuration            ⇒ scheduleSyncCleanup(s, d)
        case _                            ⇒ Cancellable.Inactive
      }

    /**
      * Registers the stage for receiving `xEvent(RunContext.PostRun)` signals.
      * This method is also available from within the `xEvent` event handler,
      * which can re-register its stage to receive this event once more.
      */
    def registerForSyncPostRunEvent(stage: StageImpl): Unit = {
      requireState(!isAsync)
      syncNeedPostRun ::= stage
    }

    @tailrec private[core] def unregisterRunner(runner: StreamRunner): Unit = {
      requireState(isAsync)
      val current = runners.get()
      val updated = current - runner
      if (runners.compareAndSet(current, updated)) {
        if (updated.isEmpty) { termination.success(()); () }
      } else unregisterRunner(runner) // another thread interfered, so we try again
    }

    private[RunSupport] def assignRunners(assignments: List[StreamRunner.Assignment]): Unit = {
      @tailrec def register(list: List[StreamRunner]): Unit = {
        @tailrec def add(remaining: List[StreamRunner], result: Set[StreamRunner]): Set[StreamRunner] =
          if (remaining ne Nil) add(remaining.tail, result + remaining.head) else result

        val current = runners.get()
        val updated = add(list, current)
        if (!runners.compareAndSet(current, updated)) {
          register(list) // another thread interfered, so we try again
        }
      }

      requireState(isAsync)
      register(StreamRunner.assignRunners(assignments, this))
    }

    private[RunSupport] def syncPostRun(): Unit = {
      @tailrec def dispatchPostRunSignal(): Unit =
        if (syncNeedPostRun ne Nil) {
          val stages = syncNeedPostRun
          syncNeedPostRun = Nil // allow for re-registration in signal handler

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

      requireState(!isAsync)
      dispatchPostRunSignal()
      runCleanups(syncCleanUp)
    }

    private def scheduleSyncCleanup(s: StageImpl, d: FiniteDuration): Cancellable = {
      val timer =
        new Cancellable with Runnable {
          def run(): Unit          = s.xEvent(SubscriptionTimeout)
          def stillActive: Boolean = syncCleanUp contains this
          def cancel(): Boolean = {
            val x = syncCleanUp.remove(this)
            (x ne syncCleanUp) && { syncCleanUp = x; true }
          }
        }
      syncCleanUp ::= timer
      timer
    }
  }

  case object PostRun
  case object SubscriptionTimeout
}
