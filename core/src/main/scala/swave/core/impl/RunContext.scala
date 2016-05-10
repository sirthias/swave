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
import swave.core.StreamEnv
import swave.core.impl.stages.Stage
import swave.core.util._

/**
 * A `RunContext` instance keeps all the contextual information required for the start of a *single* connected stream
 * network. This does not include embedded or enclosing sub-streams (streams of streams).
 */
private[swave] final class RunContext private (private var data: RunContext.Data) {

  def env: StreamEnv = data.env

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
      data.merge(other.data)
      other.data = data
    }

  def start(port: Port): Unit = {
    port.xSeal(this)

    val needXStart = data.needXStart
    data.needXStart = StageList.empty
    needXStart.foreach(RunContext.dispatchXStart)
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
  private val dispatchXStart: StageList ⇒ Unit = _.stage.xStart()
  private val dispatchXRun: StageList ⇒ Unit = _.stage.xRun()
  private val dispatchXCleanUp: StageList ⇒ Unit = _.stage.xCleanUp()

  def start(port: Port, env: StreamEnv): Unit = {
    val data = new Data(env)
    val ctx = new RunContext(data)
    ctx.start(port)
    if (ctx.data eq data) {
      ctx.dispatchPostRunSignals()
    } // else we are not the outermost context
  }

  private class Data(val env: StreamEnv) {
    var needXStart: StageList = StageList.empty
    var needXRun: StageList = StageList.empty
    var needXCleanUp: StageList = StageList.empty

    def merge(other: Data): Unit = {
      require(other.env == env)
      needXStart = needXStart.append(other.needXStart)
      needXRun = needXRun.append(other.needXRun)
      needXCleanUp = needXCleanUp.append(other.needXCleanUp)
    }
  }
}