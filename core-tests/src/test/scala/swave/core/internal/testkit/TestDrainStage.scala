/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.internal.testkit

import scala.util.control.NonFatal
import swave.core.Stage
import swave.core.impl.stages.DrainStage
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
      _inputStages = from.stageImpl :: Nil
      ready(from)
    })

  def ready(in: Inport): State = state(
    xSeal = () ⇒ {
      testCtx.trace("Received XSEAL in 'ready'")
      testCtx.trace("⇠ XSEAL")
      region.impl.registerForXStart(this)
      region.runContext.impl.registerForSyncPostRunEvent(this)
      region.runContext.impl.enablePartialRun()
      in.xSeal(region)
      awaitingXStart(in)
    })

  def awaitingXStart(in: Inport) = state(
    xStart = () => {
      testCtx.trace("Received XSTART in 'awaitingXStart'")
      if (requests.hasNext) {
        val n = requests.next()
        testCtx.run("⇠ REQUEST " + n)(in.request(n))
        if (cancelAfterOpt.isEmpty || cancelAfterOpt.get > 0) {
          fixtureState = TestFixture.State.Running
          receiving(in, n, cancelAfterOpt getOrElse requestsIterable.sum.toInt)
        } else cancel(in, n)
      } else cancel(in, 0)
    })

  def receiving(in: Inport, pending: Long, cancelAfter: Int): State = state(
    onNext = (elem, from) ⇒ {
      testCtx.trace(s"Received [$elem] from $from in state 'receiving'")
      if (from eq in) {
        recordElem(elem)
        if (pending == 1 || cancelAfter == 1) {
          if (cancelAfter > 1) {
            requireState(requests.hasNext)
            val n = requests.next()
            testCtx.run("⇠ REQUEST " + n)(in.request(n))
            receiving(in, n, cancelAfter - 1)
          } else cancel(in, pending - 1)
        } else receiving(in, pending - 1, cancelAfter - 1)
      } else throw illegalState(s"Received ['$elem'] from unexpected inport '$from' instead of inport '$in'")
    },

    onComplete = from ⇒ {
      testCtx.trace(s"Received COMPLETE from $from in state 'receiving'")
      fixtureState = TestFixture.State.Completed
      if (from eq in) completed(in)
      else throw illegalState(s"Received COMPLETE from unexpected inport '$from' instead of inport '$in'")
    },

    onError = (e, from) ⇒ {
      testCtx.trace(s"Received ERROR [$e] from $from in state 'receiving'")
      fixtureState = TestFixture.State.Error(e)
      if (from eq in) errored(in)
      else throw illegalState(s"Received ERROR [$e] from unexpected inport '$from' instead of inport '$in'")
    },

    xEvent = { case RunContext.PostRun => handlePostRun() })

  def cancel(in: Inport, pending: Long) = {
    testCtx.run("⇠ CANCEL")(in.cancel())
    fixtureState = TestFixture.State.Cancelled
    cancelled(in, pending)
  }

  def cancelled(in: Inport, pending: Long): State = state(
    onNext = (elem, from) ⇒ {
      testCtx.trace(s"Received [$elem] from $from in state 'cancelled'")
      if (from eq in) {
        if (pending > 0) cancelled(in, pending - 1)
        else throw illegalState(s"Received [$elem] from inport '$from' without prior demand")
      } else throw illegalState(s"Received [$elem] from unexpected inport '$from' instead of inport '$in'")
    },

    onComplete = from ⇒ {
      testCtx.trace(s"Received COMPLETE from $from in state 'cancelled'")
      if (from eq in) completed(in)
      else throw illegalState(s"Received COMPLETE from unexpected inport '$from' instead of inport '$in'")
    },

    onError = (e, from) ⇒ {
      testCtx.trace(s"Received ERROR [$e] from $from in state 'cancelled'")
      if (from eq in) errored(in)
      else throw illegalState(s"Received ERROR [$e] from unexpected inport '$from' instead of inport '$in")
    },

    xEvent = { case RunContext.PostRun => handlePostRun() })

  def completed(in: Inport): State = state(
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

    xEvent = { case RunContext.PostRun => handlePostRun() })

  def errored(in: Inport): State = state(
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

    xEvent = { case RunContext.PostRun => handlePostRun() })

  def handlePostRun(): State = {
    if (testCtx.hasSchedulings) {
      testCtx.trace(s"Running schedulings...")
      testCtx.processSchedulings()
      region.runContext.impl.registerForSyncPostRunEvent(this)
    }
    stay()
  }

  def pipeElemType: String = "Drain.test"
  def pipeElemParams: List[Any] = id :: Nil
}
