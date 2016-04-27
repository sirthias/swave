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
private[swave] final class StartContext private (val env: StreamEnv, _rc: RunContext) {
  private var needRunCtx: StageList = StageList.empty
  private var runContext: RunContext = _rc

  private[impl] def registerForRunCtx(stage: Stage): Unit =
    needRunCtx = stage +: needRunCtx

  private[impl] def setRunContext(rc: RunContext): Unit =
    if (runContext eq null) runContext = rc
    else requireState(runContext eq rc)
}

private[swave] object StartContext {

  def start(port: Port, env: StreamEnv): Unit = start(port, new StartContext(env, null))

  def start(port: Port, rc: RunContext): Unit = start(port, new StartContext(rc.env, rc))

  private def start(port: Port, ctx: StartContext): Unit = {
    def dispatchRunCtx(rc: RunContext): Unit = {
      @tailrec def rec(remaining: StageList): Unit =
        if (remaining.nonEmpty) {
          remaining.stage.onRunCtx(rc)
          rec(remaining.tail)
        }
      val list = ctx.needRunCtx
      ctx.needRunCtx = StageList.empty
      rec(list)
    }

    port.start(ctx)
    if (ctx.needRunCtx.nonEmpty) {
      if (ctx.runContext eq null) {
        // this is a main start
        val rc = new RunContext(ctx.env)
        dispatchRunCtx(rc)
        rc.onRunCompleted()
      } else {
        // this is a sub start
        dispatchRunCtx(ctx.runContext)
      }
    }
  }
}

/**
 * A `RunContext` instance keeps all the contextual information required for a complete run,
 * including embedded or enclosing sub-streams (streams of streams).
 */
private[swave] final class RunContext(val env: StreamEnv) {
  private[this] var requestedPostRun = StageList.empty
  private[this] var requestedCleanup = StageList.empty

  /**
   * Registers the state for receiving `Stage.PostRun` events after the initial (sync) run has completed.
   * This method is also available from within the PostRun event handler, which can re-register its stage
   * to receive this event once more.
   */
  def registerForPostRunExtra(stage: Stage): Unit = requestedPostRun = stage +: requestedPostRun

  /**
   * Registers the state for receiving `Stage.Cleanup` events after the initial (sync) run and all PostRun
   * handlers have completed.
   */
  def registerForCleanupExtra(stage: Stage): Unit = requestedCleanup = stage +: requestedCleanup

  private[impl] def onRunCompleted(): Unit = {
    @tailrec def dispatch(remaining: StageList, arg: AnyRef): Unit =
      if (remaining.nonEmpty) {
        remaining.stage.extra(arg)
        dispatch(remaining.tail, arg)
      }

    @tailrec def dispatchPostRun(): Unit =
      if (requestedPostRun.nonEmpty) {
        val registered = requestedPostRun
        requestedPostRun = StageList.empty // allow for re-registration in PostRun handler
        dispatch(registered, Stage.PostRun)
        dispatchPostRun()
      }

    dispatchPostRun()
    dispatch(requestedCleanup, Stage.Cleanup)
    requestedCleanup = StageList.empty
  }
}