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

private[swave] abstract class StreamEnvProvider {
  def env: StreamEnv
}

private[swave] final class StartContext(val env: StreamEnv) extends StreamEnvProvider {
  private[this] var needStart2: StageList = StageList.empty
  private[this] var runContext: RunContext = _

  private[impl] def registerForStart2(stage: Stage): Unit =
    needStart2 = stage +: needStart2

  private[impl] def setRunContext(rc: RunContext): Unit =
    if (runContext eq null) runContext = rc
    else requireState(runContext eq rc)

  def startStream(port: Port): Unit = {
    port.start(this)
    if (needStart2.nonEmpty) {
      if (runContext eq null) {
        // we are the main StartContext
        val rc = new RunContext(env)
        dispatchStart2(rc)
        rc.postStart()
      } else {
        // we are a sub StartContext
        dispatchStart2(runContext)
      }
    }
  }

  private def dispatchStart2(rc: RunContext): Unit = {
    @tailrec def rec(remaining: StageList): Unit =
      if (remaining.nonEmpty) {
        remaining.stage.start2(rc)
        rec(remaining.tail)
      }
    val list = needStart2
    needStart2 = StageList.empty
    rec(list)
  }

}

private[swave] final class RunContext(val env: StreamEnv) extends StreamEnvProvider {
  private[this] var requestedPostStart = StageList.empty
  private[this] var requestedCleanup = StageList.empty

  def registerForPostStartExtra(stage: Stage): Unit = requestedPostStart = stage +: requestedPostStart
  def registerForCleanupExtra(stage: Stage): Unit = requestedCleanup = stage +: requestedCleanup

  private[impl] def postStart(): Unit = {
    @tailrec def dispatch(remaining: StageList, arg: AnyRef): Unit =
      if (remaining.nonEmpty) {
        remaining.stage.extra(arg)
        dispatch(remaining.tail, arg)
      }

    @tailrec def dispatchPostStart(): Unit =
      if (requestedPostStart.nonEmpty) {
        val registered = requestedPostStart
        requestedPostStart = StageList.empty // allow for re-registration in PostStart handler
        dispatch(registered, Stage.PostStart)
        dispatchPostStart()
      }

    dispatchPostStart()

    dispatch(requestedCleanup, Stage.Cleanup)
    requestedCleanup = StageList.empty
  }
}