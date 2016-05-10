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

package swave.testkit.impl

import swave.core.PipeElem
import swave.core.impl.stages.drain.DrainStage
import swave.core.impl.{ RunContext, Inport }
import swave.core.util._
import swave.testkit.TestFixture

private[testkit] final class TestDrainStage(
    val id: Int,
    val requestsIterable: Iterable[Long],
    val cancelAfter: Option[Int],
    testCtx: TestContext) extends DrainStage with TestStage with PipeElem.Drain.Test {

  private[this] val requests: Iterator[Long] = requestsIterable.iterator

  override def toString: String = "Output " + id

  def formatLong = {
    val requests = requestsIterable.map(r ⇒ if (r == Long.MaxValue) "Long.MaxValue" else r.toString)
    s"""|Output  : id = $id, state = $fixtureState / $stateName
        |script  : size = $scriptedSize, requests = [${requests.mkString(", ")}], cancelAfter = $cancelAfter
        |received: size = $resultSize, elems = [${result.mkString(", ")}]""".stripMargin
  }

  def scriptedSize: Int = cancelAfter getOrElse requestsIterable.sum.toInt

  // format: OFF

  initialState(awaitingOnSubscribe)

  private def awaitingOnSubscribe =
    state(name = "awaitingOnSubscribe",

      onSubscribe = in ⇒ {
        testCtx.trace(s"Received ONSUBSCRIBE from $in in 'initialState'")
        setInputElem(in.pipeElem)
        ready(in)
      })

  private def ready(in: Inport): State =
    fullState(name = "ready",

      xSeal = ctx ⇒ {
        testCtx.trace("Received XSEAL in 'ready'")
        configureFrom(ctx.env)
        testCtx.trace("⇠ XSEAL")
        in.xSeal(ctx)

        ctx.registerForXStart(this)
        ctx.registerForXRun(this)
        awaitingXStart(ctx, in)
      })

  private def awaitingXStart(ctx: RunContext, in: Inport) =
    state(name = "awaitingXStart",

      xStart = () => {
        testCtx.trace("Received XSTART in 'awaitingXStart'")
        if (requests.hasNext) {
          val n = requests.next()
          testCtx.run("⇠ REQUEST " + n)(in.request(n))
          if (cancelAfter.isEmpty || cancelAfter.get > 0) {
            fixtureState = TestFixture.State.Running
            receiving(ctx, in, n, cancelAfter getOrElse requestsIterable.sum.toInt)
          } else cancel(ctx, in, n)
        } else cancel(ctx, in, 0)
      })

  private def receiving(ctx: RunContext, in: Inport, pending: Long, cancelAfter: Int): State =
    state(name = "receiving",

      onNext = (elem, from) ⇒ {
        testCtx.trace(s"Received [$elem] from $from in state 'receiving'")
        if (from eq in) {
          recordElem(elem)
          if (pending == 1 || cancelAfter == 1) {
            if (cancelAfter > 1) {
              requireState(requests.hasNext)
              val n = requests.next()
              testCtx.run("⇠ REQUEST " + n)(in.request(n))
              receiving(ctx, in, n, cancelAfter - 1)
            } else cancel(ctx, in, pending - 1)
          } else receiving(ctx, in, pending - 1, cancelAfter - 1)
        } else illegalState(s"Received ['$elem'] from unexpected inport '$from' instead of inport '$in' in $this")
      },

      onComplete = from ⇒ {
        testCtx.trace(s"Received COMPLETE from $from in state 'receiving'")
        fixtureState = TestFixture.State.Completed
        if (from eq in) completed(ctx, in)
        else illegalState(s"Received COMPLETE from unexpected inport '$from' instead of inport '$in' in $this")
      },

      onError = (e, from) ⇒ {
        testCtx.trace(s"Received ERROR [$e] from $from in state 'receiving'")
        fixtureState = TestFixture.State.Error(e)
        if (from eq in) errored(ctx, in)
        else illegalState(s"Received ERROR [$e] from unexpected inport '$from' instead of inport '$in' in $this")
      },

      xRun = () => handlePostRun(ctx))

  private def cancel(ctx: RunContext, in: Inport, pending: Long) = {
    testCtx.run("⇠ CANCEL")(in.cancel())
    fixtureState = TestFixture.State.Cancelled
    cancelled(ctx, in, pending)
  }

  private def cancelled(ctx: RunContext, in: Inport, pending: Long): State =
    state(name = "cancelled",

      onNext = (elem, from) ⇒ {
        testCtx.trace(s"Received [$elem] from $from in state 'cancelled'")
        if (from eq in) {
          if (pending > 0) cancelled(ctx, in, pending - 1)
          else illegalState(s"Received [$elem] from inport '$from' without prior demand in $this")
        } else illegalState(s"Received [$elem] from unexpected inport '$from' instead of inport '$in' in $this")
      },

      onComplete = from ⇒ {
        testCtx.trace(s"Received COMPLETE from $from in state 'cancelled'")
        if (from eq in) completed(ctx, in)
        else illegalState(s"Received COMPLETE from unexpected inport '$from' instead of inport '$in' in $this")
      },

      onError = (e, from) ⇒ {
        testCtx.trace(s"Received ERROR [$e] from $from in state 'cancelled'")
        if (from eq in) errored(ctx, in)
        else illegalState(s"Received ERROR [$e] from unexpected inport '$from' instead of inport '$in in $this")
      },

      xRun = () => handlePostRun(ctx))

  private def completed(ctx: RunContext, in: Inport): State =
    state(name = "completed",

      onNext = (elem, from) ⇒ {
        testCtx.trace(s"Received [$elem] from $from in state 'completed'")
        if (from eq in) illegalState(s"Received [$elem] from inport '$from' after completion in $this")
        else illegalState(s"Received [$elem] from unexpected inport '$from' instead of inport '$in' in $this")
      },

      onComplete = from ⇒ {
        testCtx.trace(s"Received COMPLETE from $from in state 'completed'")
        if (from eq in) illegalState(s"Received double COMPLETE from inport '$from' in $this")
        else illegalState(s"Received COMPLETE from unexpected inport '$from' instead of inport '$in' in $this")
      },

      onError = (e, from) ⇒ {
        testCtx.trace(s"Received ERROR [$e] from $from in state 'completed'")
        if (from eq in) illegalState(s"Received ERROR [$e] after COMPLETE from inport '$from' in $this")
        else illegalState(s"Received ERROR [$e] from unexpected inport '$from' instead of inport '$in' in $this")
      },

      xRun = () => handlePostRun(ctx))

  private def errored(ctx: RunContext, in: Inport): State =
    state(name = "errored",

      onNext = (elem, from) ⇒ {
        testCtx.trace(s"Received [$elem] from $from in state 'errored'")
        if (from eq in) illegalState(s"Received [$elem] after ERROR from inport '$from' in $this")
        else illegalState(s"Received [$elem] from unexpected inport '$from' instead of inport '$in' in $this")
      },

      onComplete = from ⇒ {
        testCtx.trace(s"Received COMPLETE from $from in state 'errored'")
        if (from eq in) illegalState(s"Received COMPLETE after ERROR from inport '$from' in $this")
        else illegalState(s"Received COMPLETE from unexpected inport '$from' instead of inport '$in' in $this")
      },

      onError = (e, from) ⇒ {
        testCtx.trace(s"Received ERROR [$e] from $from in state 'errored'")
        if (from eq in) illegalState(s"Received onError($e) after ERROR from inport '$from' in $this")
        else illegalState(s"Received ERROR [$e] from unexpected inport '$from' instead of inport '$in' in $this")
      },

      xRun = () => handlePostRun(ctx))

  private def handlePostRun(ctx: RunContext): State = {
    if (testCtx.hasSchedulings) {
      testCtx.trace(s"Running schedulings...")
      testCtx.processSchedulings()
      ctx.registerForXRun(this)
    }
    stay()
  }

  def pipeElemType: String = "Drain.test"
  def pipeElemParams: List[Any] = id :: Nil
}