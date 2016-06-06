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

import scala.annotation.tailrec
import swave.core.impl.StreamRunner.StageDispatcherIdListMap
import swave.core.StreamEnv
import swave.core.impl.stages.Stage
import swave.core.util._

/**
 * A `RunContext` instance keeps all the contextual information required for the start of a *single* connected stream
 * network. This does not include embedded or enclosing sub-streams (streams of streams).
 */
private[swave] final class RunContext(val port: Port)(implicit val env: StreamEnv) {
  private var data = new RunContext.Data
  private var isSubContext = false
  private var isAsyncRun = false

  def seal(): Unit = {
    if (port.isSealed) throw new IllegalStateException(port + " is already sealed. It cannot be sealed a second time.")
    port.xSeal(this)
    if (data.needRunner.nonEmpty) {
      StreamRunner.assignRunners(data.needRunner, env)
      data.needRunner = StageDispatcherIdListMap.empty
      isAsyncRun = true
    }
  }

  /**
   * Registers a stage for assignment of a [[StreamRunner]] with the given `dispatcherId`.
   * If the `dispatcherId` is empty the default dispatcher will be assigned
   * if no other non-default assignment has previously been made.
   */
  def registerForRunnerAssignment(stage: Stage, dispatcherId: String): Unit =
    data.needRunner = StageDispatcherIdListMap(stage, dispatcherId, data.needRunner)

  /**
   * Registers the stage for receiving `xStart` signals.
   */
  def registerForXStart(stage: Stage): Unit = data.needXStart = stage +: data.needXStart

  /**
   * Registers the stage for receiving `xRun` signals.
   * This method is also available from within the `xRun` event handler,
   * which can re-register its stage to receive this event once more.
   */
  def registerForXRun(stage: Stage): Unit = data.needXRun = stage +: data.needXRun

  /**
   * Registers the stage for receiving `xCleanUp` signals.
   */
  def registerForXCleanUp(stage: Stage): Unit = data.needXCleanUp = stage +: data.needXCleanUp

  def attach(other: RunContext): Unit =
    if (other.data ne data) {
      requireArg(other.env == env)
      data.needRunner = data.needRunner.append(other.data.needRunner)
      data.needXStart = data.needXStart.append(other.data.needXStart)
      data.needXRun = data.needXRun.append(other.data.needXRun)
      data.needXCleanUp = data.needXCleanUp.append(other.data.needXCleanUp)
      other.data = data
      other.isSubContext = true
    }

  def sealAndStartSubStream(port: Port): Unit = {
    val subCtx = new RunContext(port)
    subCtx.isSubContext = true
    subCtx.seal()
    subCtx.start()
  }

  def start(): Unit = {
    val needXStart = data.needXStart
    data.needXStart = StageList.empty
    needXStart.foreach(if (isAsyncRun) RunContext.dispatchAsyncXStart else RunContext.dispatchXStart)
    if (!isSubContext && !isAsyncRun) dispatchPostRunSignals()
  }

  private def dispatchPostRunSignals(): Unit = {
    @tailrec def dispatchXRun(): Unit = {
      if (data.needXRun.nonEmpty) {
        val needXRun = data.needXRun
        data.needXRun = StageList.empty // allow for re-registration in xRun handler
        needXRun.foreach(RunContext.dispatchXRun)
        dispatchXRun()
      }
    }

    dispatchXRun()
    data.needXCleanUp.foreach(RunContext.dispatchXCleanUp)
    data.needXCleanUp = StageList.empty
  }
}

private[swave] object RunContext {
  private val dispatchAsyncXStart: StageList ⇒ Unit = sl ⇒ sl.stage.runner.enqueueXStart(sl.stage)
  private val dispatchXStart: StageList ⇒ Unit = _.stage.xStart()
  private val dispatchXRun: StageList ⇒ Unit = _.stage.xRun()
  private val dispatchXCleanUp: StageList ⇒ Unit = _.stage.xCleanUp()

  private class Data {
    var needRunner: StageDispatcherIdListMap = StageDispatcherIdListMap.empty
    var needXStart: StageList = StageList.empty
    var needXRun: StageList = StageList.empty
    var needXCleanUp: StageList = StageList.empty
  }
}