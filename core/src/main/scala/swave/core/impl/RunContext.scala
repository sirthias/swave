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

import scala.concurrent.duration.{ FiniteDuration, Duration }
import swave.core.{ Cancellable, StreamEnv, IllegalAsyncBoundaryException, IllegalReuseException }
import swave.core.impl.stages.Stage
import swave.core.impl.StreamRunner._
import swave.core.util._

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
      data.runnerClampings.foreach { clamping ⇒
        if (clamping.stage0.runner ne clamping.stage1.runner)
          throw new IllegalAsyncBoundaryException(s"Stages `${clamping.stage0.getClass.getSimpleName}` and " +
            s"`${clamping.stage1.getClass.getSimpleName}` must not be in two different async regions!")
      }
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
   * Requests that the two given stages will never be assigned differing [[StreamRunner]] instances.
   * If the user requests such an illegal assignment an [[IllegalAsyncBoundaryException]] will be thrown.
   */
  def registerForRunnerClamping(stage0: Stage, stage1: Stage): Unit =
    data.runnerClampings = StageStageList(stage0, stage1, data.runnerClampings)

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
      data.needRunner = data.needRunner.append(other.data.needRunner)
      data.runnerClampings = data.runnerClampings.append(other.data.runnerClampings)
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
    if (isSubContext && isAsyncRun && !parent.isAsyncRun) {
      // TODO: can we upgrade the parent stream from sync to async mode on the fly?
      throw new IllegalAsyncBoundaryException("Cannot start an asynchronous sub-stream underneath a synchronous " +
        "parent stream. Please make the parent stream asynchronous by marking a `Drain` with `.async()` or " +
        "adding an explicit asynchronous boundary.")
    }
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

  private val dispatchAsyncXStart: Stage ⇒ Unit = stage ⇒ stage.runner.enqueue(new StreamRunner.Message.XStart(stage))
  private val dispatchXStart: Stage ⇒ Unit = _.xStart()
  private val dispatchPostRun: Stage ⇒ Unit = _.xEvent(PostRun)
  private val dispatchCleanup: Runnable ⇒ Unit = _.run()

  private class Data {
    var needRunner = StageDispatcherIdListMap.empty
    var runnerClampings = StageStageList.empty
    var needXStart = List.empty[Stage]
    var needPostRun = List.empty[Stage]
    var cleanup = List.empty[Runnable]
  }
}