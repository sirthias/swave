/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.internal.testkit

import scala.util.control.NonFatal
import swave.core.Stage
import swave.core.impl.stages.drain.DrainStage
import swave.core.impl.{Inport, RunContext}
import swave.core.macros._

@StageImplementation(fullInterceptions = true)
private[testkit] final class TestDrainStage(val id: Int,
                                            val requestsIterable: Iterable[Long],
                                            val cancelAfterOpt: Option[Int],
                                            testCtx: TestContext) extends DrainStage with TestStage {

  def kind = Stage.Kind.Drain.Test(id)

  private[this] val requests: Iterator[Long] = requestsIterable.iterator

  override def toString: String = "Output " + id

  def formatLong = {
    def ts(o: AnyRef) =
      try o.toString
      catch { case NonFatal(e) ⇒ s"<${e.getClass.getSimpleName}>" }
    val requests = requestsIterable.map(r ⇒ if (r == Long.MaxValue) "Long.MaxValue" else r.toString)
    s"""|Output  : id = $id, state = $fixtureState / $stateName
        |script  : size = $scriptedSize, requests = [${requests.mkString(", ")}], cancelAfter = $cancelAfterOpt
        |received: size = $resultSize, elems = [${result[AnyRef].map(ts).mkString(", ")}]""".stripMargin
  }

  def scriptedSize: Int = cancelAfterOpt getOrElse requestsIterable.sum.toInt

  // format: OFF

  initialState(awaitingOnSubscribe())

  def awaitingOnSubscribe() = state(
    onSubscribe = from ⇒ {
      testCtx.trace(s"Received ONSUBSCRIBE from $from in 'initialState'")
      _inputStages = from.stage :: Nil
      ready(from)
    })

  def ready(in: Inport): State = state(
    xSeal = c ⇒ {
      testCtx.trace("Received XSEAL in 'ready'")
      configureFrom(c)
      c.allowSyncUnstopped()
      testCtx.trace("⇠ XSEAL")
      in.xSeal(c)

      c.registerForXStart(this)
      c.registerForPostRunEvent(this)
      awaitingXStart(c, in)
    })

  def awaitingXStart(ctx: RunContext, in: Inport) = state(
    xStart = () => {
      testCtx.trace("Received XSTART in 'awaitingXStart'")
      if (requests.hasNext) {
        val n = requests.next()
        testCtx.run("⇠ REQUEST " + n)(in.request(n))
        if (cancelAfterOpt.isEmpty || cancelAfterOpt.get > 0) {
          fixtureState = TestFixture.State.Running
          receiving(ctx, in, n, cancelAfterOpt getOrElse requestsIterable.sum.toInt)
        } else cancel(ctx, in, n)
      } else cancel(ctx, in, 0)
    })

  def receiving(ctx: RunContext, in: Inport, pending: Long, cancelAfter: Int): State = state(
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
      } else throw illegalState(s"Received ['$elem'] from unexpected inport '$from' instead of inport '$in'")
    },

    onComplete = from ⇒ {
      testCtx.trace(s"Received COMPLETE from $from in state 'receiving'")
      fixtureState = TestFixture.State.Completed
      if (from eq in) completed(ctx, in)
      else throw illegalState(s"Received COMPLETE from unexpected inport '$from' instead of inport '$in'")
    },

    onError = (e, from) ⇒ {
      testCtx.trace(s"Received ERROR [$e] from $from in state 'receiving'")
      fixtureState = TestFixture.State.Error(e)
      if (from eq in) errored(ctx, in)
      else throw illegalState(s"Received ERROR [$e] from unexpected inport '$from' instead of inport '$in'")
    },

    xEvent = { case RunContext.PostRun => handlePostRun(ctx) })

  def cancel(ctx: RunContext, in: Inport, pending: Long) = {
    testCtx.run("⇠ CANCEL")(in.cancel())
    fixtureState = TestFixture.State.Cancelled
    cancelled(ctx, in, pending)
  }

  def cancelled(ctx: RunContext, in: Inport, pending: Long): State = state(
    onNext = (elem, from) ⇒ {
      testCtx.trace(s"Received [$elem] from $from in state 'cancelled'")
      if (from eq in) {
        if (pending > 0) cancelled(ctx, in, pending - 1)
        else throw illegalState(s"Received [$elem] from inport '$from' without prior demand")
      } else throw illegalState(s"Received [$elem] from unexpected inport '$from' instead of inport '$in'")
    },

    onComplete = from ⇒ {
      testCtx.trace(s"Received COMPLETE from $from in state 'cancelled'")
      if (from eq in) completed(ctx, in)
      else throw illegalState(s"Received COMPLETE from unexpected inport '$from' instead of inport '$in'")
    },

    onError = (e, from) ⇒ {
      testCtx.trace(s"Received ERROR [$e] from $from in state 'cancelled'")
      if (from eq in) errored(ctx, in)
      else throw illegalState(s"Received ERROR [$e] from unexpected inport '$from' instead of inport '$in")
    },

    xEvent = { case RunContext.PostRun => handlePostRun(ctx) })

  def completed(ctx: RunContext, in: Inport): State = state(
    onNext = (elem, from) ⇒ {
      testCtx.trace(s"Received [$elem] from $from in state 'completed'")
      if (from eq in) throw illegalState(s"Received [$elem] from inport '$from' after completion")
      else throw illegalState(s"Received [$elem] from unexpected inport '$from' instead of inport '$in'")
    },

    onComplete = from ⇒ {
      testCtx.trace(s"Received COMPLETE from $from in state 'completed'")
      if (from eq in) throw illegalState(s"Received double COMPLETE from inport '$from'")
      else throw illegalState(s"Received COMPLETE from unexpected inport '$from' instead of inport '$in'")
    },

    onError = (e, from) ⇒ {
      testCtx.trace(s"Received ERROR [$e] from $from in state 'completed'")
      if (from eq in) throw illegalState(s"Received ERROR [$e] after COMPLETE from inport '$from'")
      else throw illegalState(s"Received ERROR [$e] from unexpected inport '$from' instead of inport '$in'")
    },

    xEvent = { case RunContext.PostRun => handlePostRun(ctx) })

  def errored(ctx: RunContext, in: Inport): State = state(
    onNext = (elem, from) ⇒ {
      testCtx.trace(s"Received [$elem] from $from in state 'errored'")
      if (from eq in) throw illegalState(s"Received [$elem] after ERROR from inport '$from'")
      else throw illegalState(s"Received [$elem] from unexpected inport '$from' instead of inport '$in'")
    },

    onComplete = from ⇒ {
      testCtx.trace(s"Received COMPLETE from $from in state 'errored'")
      if (from eq in) throw illegalState(s"Received COMPLETE after ERROR from inport '$from'")
      else throw illegalState(s"Received COMPLETE from unexpected inport '$from' instead of inport '$in'")
    },

    onError = (e, from) ⇒ {
      testCtx.trace(s"Received ERROR [$e] from $from in state 'errored'")
      if (from eq in) throw illegalState(s"Received onError($e) after ERROR from inport '$from'")
      else throw illegalState(s"Received ERROR [$e] from unexpected inport '$from' instead of inport '$in'")
    },

    xEvent = { case RunContext.PostRun => handlePostRun(ctx) })

  def handlePostRun(ctx: RunContext): State = {
    if (testCtx.hasSchedulings) {
      testCtx.trace(s"Running schedulings...")
      testCtx.processSchedulings()
      ctx.registerForPostRunEvent(this)
    }
    stay()
  }

  def pipeElemType: String = "Drain.test"
  def pipeElemParams: List[Any] = id :: Nil
}
