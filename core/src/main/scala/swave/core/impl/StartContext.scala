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
 * A `StartContext` instance keeps all the contextual information required for the start of a *single* connected stream
 * network. This does not include embedded or enclosing sub-streams (streams of streams).
 */
private[swave] final class StartContext private (private var data: StartContext.Data) {

  def env: StreamEnv = data.env

  /**
   * Registers the stage for receiving `Stage.PostRun` events after the initial (sync) run has completed.
   * This method is also available from within the PostRun event handler, which can re-register its stage
   * to receive this event once more.
   */
  def registerForPostRunExtra(stage: Stage): Unit = data.needPostRunExtra = stage +: data.needPostRunExtra

  /**
   * Registers the stage for receiving `Stage.Cleanup` events after the initial (sync) run and all PostRun
   * handlers have completed.
   */
  def registerForCleanupExtra(stage: Stage): Unit = data.needCleanUpExtra = stage +: data.needCleanUpExtra

  def attach(other: StartContext): Unit =
    if (other.data ne data) {
      data.merge(other.data)
      other.data = data
    }

  def start(port: Port): Unit = {
    val initialDataInstance = data
    port.start(this)

    if (data eq initialDataInstance) {
      @tailrec def dispatch(remaining: StageList, arg: AnyRef): Unit =
        if (remaining.nonEmpty) {
          remaining.stage.onExtraSignal(arg)
          dispatch(remaining.tail, arg)
        }

      @tailrec def dispatchPostRun(): Unit =
        if (data.needPostRunExtra.nonEmpty) {
          val registered = data.needPostRunExtra
          data.needPostRunExtra = StageList.empty // allow for re-registration in PostRun handler
          dispatch(registered, Stage.PostRun)
          dispatchPostRun()
        }

      dispatchPostRun()
      dispatch(data.needCleanUpExtra, Stage.Cleanup)
      data.needCleanUpExtra = StageList.empty
    } // else we are not the outermost context
  }
}

private[swave] object StartContext {

  def start(port: Port, env: StreamEnv): Unit =
    new StartContext(new Data(env)).start(port)

  private class Data(val env: StreamEnv) {
    var needPostRunExtra: StageList = StageList.empty
    var needCleanUpExtra: StageList = StageList.empty

    def merge(other: Data): Unit = {
      require(other.env == env)
      needPostRunExtra = needPostRunExtra.append(other.needPostRunExtra)
      needCleanUpExtra = needCleanUpExtra.append(other.needCleanUpExtra)
    }
  }
}