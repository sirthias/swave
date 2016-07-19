/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl

import scala.concurrent.duration.{ FiniteDuration, Duration }
import swave.core.impl.stages.Stage
import swave.core.impl.StreamRunner._
import swave.core.macros._
import swave.core.util._
import swave.core._

/**
 * A `RunContext` instance keeps all the contextual information required for the start of a *single* connected stream
 * network. This does not include embedded or enclosing sub-streams (streams of streams).
 *
 * CAUTION: If the run is async, do not mutate anymore after the sealing phase is over!
 */
private[swave] final class RunContext(val port: Port)(implicit val env: StreamEnv) {
  import RunContext._
  private var data = new Data
  private var isAsyncRun = false
  private var parent: RunContext = _

  private def isSubContext = parent ne null

  def seal(): Unit = {
    if (port.isSealed) throw new IllegalReuseException(port + " is already sealed. It cannot be sealed a second time.")
    port.xSeal(this)
    if (data.needRunner.nonEmpty) {
      StreamRunner.assignRunners(data.needRunner)
      data.needRunner = StageDispatcherIdListMap.empty
      isAsyncRun = true
    }
  }

  /**
   * Registers a stage for assignment of a [[StreamRunner]] with the given `dispatcherId`.
   * If the `dispatcherId` is empty the default dispatcher will be assigned
   * if no other non-default assignment has previously been made.
   */
  def registerForRunnerAssignment(stage: Stage, dispatcherId: String = ""): Unit =
    data.needRunner = StageDispatcherIdListMap(stage, dispatcherId, data.needRunner)

  /**
   * Registers the stage for receiving `xStart` signals.
   */
  def registerForXStart(stage: Stage): Unit = data.needXStart ::= stage

  /**
   * Registers the stage for receiving `xEvent(RunContext.PostRun)` signals.
   * This method is also available from within the `xEvent` event handler,
   * which can re-register its stage to receive this event once more.
   *
   * Note: This event is only available for synchronous runs!
   */
  def registerForPostRunEvent(stage: Stage): Unit = data.needPostRun ::= stage

  def scheduleSubscriptionTimeout(stage: Stage, delay: Duration): Cancellable =
    delay match {
      case d: FiniteDuration ⇒
        if (isAsyncRun) {
          stage.runner.scheduleEvent(stage, d, SubscriptionTimeout)
        } else {
          val timer =
            new Cancellable with Runnable {
              def run() = stage.xEvent(SubscriptionTimeout)
              def stillActive = data.cleanup contains stage
              def cancel() = {
                val x = data.cleanup.remove(this)
                (x ne data.cleanup) && {
                  data.cleanup = x
                  true
                }
              }
            }
          data.cleanup ::= timer
          timer
        }
      case _ ⇒ Cancellable.Inactive
    }

  def attach(other: RunContext): Unit =
    if (other.data ne data) {
      requireArg(other.env == env)
      data.needRunner = data.needRunner append other.data.needRunner
      data.needXStart = data.needXStart ::: other.data.needXStart
      data.needPostRun = data.needPostRun ::: other.data.needPostRun
      data.cleanup = data.cleanup ::: other.data.cleanup
      other.data = data
      other.parent = this
    }

  def sealAndStartSubStream(port: Port): Unit = {
    val subCtx = new RunContext(port)
    subCtx.parent = this
    subCtx.seal()
    subCtx.start()
  }

  def start(): Unit = {
    val needXStart = data.needXStart
    data.needXStart = Nil
    needXStart.foreach(if (isAsyncRun) dispatchAsyncXStart else dispatchXStart)

    if (!isSubContext && !isAsyncRun) dispatchPostRunSignal()
    data.cleanup.foreach(dispatchCleanup)
  }

  private def dispatchPostRunSignal(): Unit =
    if (data.needPostRun.nonEmpty) {
      val needPostRun = data.needPostRun
      data.needPostRun = Nil // allow for re-registration in xRun handler
      needPostRun.foreach(dispatchPostRun)
      dispatchPostRunSignal()
    }
}

private[swave] object RunContext {
  case object PostRun
  case object SubscriptionTimeout

  private val dispatchAsyncXStart: Stage ⇒ Unit = stage ⇒ stage.runner.enqueueXStart(stage)
  private val dispatchXStart: Stage ⇒ Unit = _.xStart()
  private val dispatchPostRun: Stage ⇒ Unit = _.xEvent(PostRun)
  private val dispatchCleanup: Runnable ⇒ Unit = _.run()

  private class Data {
    var needRunner = StageDispatcherIdListMap.empty
    var needXStart = List.empty[Stage]
    var needPostRun = List.empty[Stage]
    var cleanup = List.empty[Runnable]
  }
}
