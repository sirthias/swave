/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.annotation.tailrec
import swave.core.impl.stages.StageImpl
import swave.core.util._
import swave.core._

/**
  * A `RunContext` instance keeps all the contextual information required for the start of a *single* connected stream
  * network. This does not include embedded or enclosing sub-streams (streams of streams).
  *
  * CAUTION: If the run is async, do not mutate anymore after the sealing phase is over!!!
  */
private[swave] final class RunContext(val port: Port)(implicit val env: StreamEnv) {

  import RunContext._

  // the GlobalContext keeps the state for the global run, i.e. the outermost `run()` of all nested sub-streams
  private var globalCtx         = new GlobalContext(this)
  private var runnerAssignments = List.empty[StreamRunner.Assignment]
  private var needXStart        = List.empty[StageImpl]

  private def isMainContext = globalCtx.owner eq this

  def allowSyncUnstopped(): Unit = globalCtx.allowSyncUnstopped = true

  // must be called before sealing!
  def linkToParentContext(parentContext: RunContext): Unit =
    globalCtx = parentContext.globalCtx

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
  def registerForXStart(stage: StageImpl): Unit =
    needXStart ::= stage

  /**
    * Registers the stage for receiving `xEvent(RunContext.PostRun)` signals.
    * This method is also available from within the `xEvent` event handler,
    * which can re-register its stage to receive this event once more.
    *
    * Note: This event is only available for synchronous runs!
    */
  def registerForPostRunEvent(stage: StageImpl): Unit =
    globalCtx.syncNeedPostRun ::= stage

  /**
    * Seals the stream by sending an `xSeal` signal through the stream graph starting from `port`.
    */
  def seal(): Unit = {
    if (port.isSealed) {
      val msg = port + " is already sealed. It cannot be sealed a second time. " +
          "Are you trying to reuse a Spout, Drain, Pipe or Module?"
      throw new IllegalReuseException(msg)
    }
    port.xSeal(this)
    if (runnerAssignments.nonEmpty) {
      globalCtx.registerRunners(StreamRunner.assignRunners(runnerAssignments))
    }
  }

  /**
    * Schedules a [[Cancellable]] timeout of the given `delay`.
    * If the returned [[Cancellable]] isn't cancelled in time a [[SubscriptionTimeout]] event will be dispatched
    * to the given `stage`.
    *
    * This method differs from the regular one available on [[Scheduler]] in that is also supports
    * synchronous runs. If the run is synchronous the `delay` is disregarded and the timeout logic
    * runs when the global run has terminated (unless it's cancelled beforehand).
    */
  def scheduleSubscriptionTimeout(stage: StageImpl, delay: Duration): Cancellable =
    delay match {
      case d: FiniteDuration if globalCtx.isSyncRun ⇒
        val timer =
          new Cancellable with Runnable {
            def run() = stage.xEvent(SubscriptionTimeout)

            def stillActive = globalCtx.syncCleanup contains this

            def cancel() = {
              val x = globalCtx.syncCleanup.remove(this)
              (x ne globalCtx.syncCleanup) && {
                globalCtx.syncCleanup = x
                true
              }
            }
          }
        globalCtx.syncCleanup ::= timer
        timer

      case d: FiniteDuration ⇒ stage.runner.scheduleEvent(stage, d, SubscriptionTimeout)

      case _ ⇒ Cancellable.Inactive
    }

  /**
    * Starts (and potentially runs) the stream.
    */
  def start(): Unit =
    if (globalCtx.isSyncRun) {
      needXStart.foreach(dispatchSyncXStart)
      if (isMainContext) {
        globalCtx.dispatchSyncPostRunSignal()
        globalCtx.syncCleanup.foreach(dispatchSyncCleanup)
        if (!globalCtx.allowSyncUnstopped && !port.isStopped) throw new UnterminatedSynchronousStreamException
      }
    } else needXStart.foreach(dispatchAsyncXStart)

  /**
    * Seals and starts the given port with the same [[StreamEnv]] as this RunContext.
    *
    * CAUTION: This method will throw if there is any problem with sealing or starting the sub stream.
    */
  def sealAndStartSubStream(port: Port): Unit = {
    val subCtx = new RunContext(port)
    subCtx.seal()
    subCtx.start()
  }
}

private[swave] object RunContext {
  case object PostRun
  case object SubscriptionTimeout

  private val dispatchAsyncXStart: StageImpl ⇒ Unit = stage ⇒ stage.runner.enqueueXStart(stage)
  private val dispatchSyncXStart: StageImpl ⇒ Unit  = _.xStart()
  private val dispatchSyncPostRun: StageImpl ⇒ Unit = _.xEvent(PostRun)
  private val dispatchSyncCleanup: Runnable ⇒ Unit  = _.run()

  private final class GlobalContext(val owner: RunContext) {
    // only used in async runs
    // Note: This list might contain the same StreamRunner instance several times,
    // if an async region contains nested streams!!
    val runners = new AtomicReference[List[StreamRunner]](Nil)

    // only used in synchronous runs
    var syncNeedPostRun    = List.empty[StageImpl]
    var syncCleanup        = List.empty[Runnable]
    var allowSyncUnstopped = false

    def isSyncRun = runners.get().isEmpty

    @tailrec def registerRunners(list: List[StreamRunner]): Unit = {
      val current = runners.get()
      val updated = current.reverse_:::(list)
      if (!runners.compareAndSet(current, updated))
        registerRunners(list) // try again
    }

    @tailrec def dispatchSyncPostRunSignal(): Unit =
      if (syncNeedPostRun.nonEmpty) {
        val needPostRun = syncNeedPostRun
        syncNeedPostRun = Nil // allow for re-registration in signal handler
        needPostRun.foreach(dispatchSyncPostRun)
        dispatchSyncPostRunSignal()
      }
  }
}
