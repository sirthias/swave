/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.internal.testkit

import scala.annotation.tailrec
import swave.core.macros.StageImplementation
import swave.core.Stage
import swave.core.impl.Outport
import swave.core.impl.stages.SpoutStage

@StageImplementation(fullInterceptions = true)
private[testkit] final class TestSpoutStage(val id: Int,
                                            val elemsIterable: Iterable[AnyRef],
                                            val termination: Option[Throwable],
                                            ctx: TestContext) extends SpoutStage with TestStage {

  private[this] val elems: Iterator[AnyRef] = elemsIterable.iterator

  def kind = Stage.Kind.Spout.Test(id)

  override def toString: String = "Input  " + id

  def formatLong =
    s"""|Input   : id = $id, state = $fixtureState / $stateName
        |script  : size = $scriptedSize, elem = [${elemsIterable.mkString(", ")}], termination = $termination
        |produced: size = $resultSize, elems = [${result.mkString(", ")}]""".stripMargin

  def scriptedSize: Int = elemsIterable.size

  // format: OFF

  initialState(awaitingSubscribe())

  def awaitingSubscribe() = state(
    subscribe = from ⇒ {
      ctx.trace(s"Received SUBSCRIBE from $from in 'initialState'")
      ctx.trace("⇢ ONSUBSCRIBE")
      _outputStages = from.stageImpl :: Nil
      from.onSubscribe()
      ready(from)
    })

  def ready(out: Outport): State = state(
    xSeal = c ⇒ {
      ctx.trace("Received XSEAL in 'ready'")
      configureFrom(c)
      c.disableErrorOnSyncUnstopped()
      ctx.trace("⇢ XSEAL")
      out.xSeal(c)
      if (elems.hasNext) {
        fixtureState = TestFixture.State.Running
        producing(out)
      } else {
        ctx.trace("Registering for XSTART reception")
        c.registerForXStart(this)
        awaitingXStart(out)
      }
    })

  def awaitingXStart(out: Outport) = state(
    xStart = () => {
      ctx.trace("Received XSTART in 'ready'")
      terminate(out)
    })

  def terminate(out: Outport) =
    termination match {
      case None ⇒
        ctx.run("⇢ COMPLETE")(out.onComplete())
        fixtureState = TestFixture.State.Completed
        completed(out)
      case Some(e) ⇒
        ctx.run("⇢ ERROR")(out.onError(e))
        fixtureState = TestFixture.State.Error(e)
        errored(out)
    }

  def producing(out: Outport): State = state(
    request = (n, from) ⇒ {
      ctx.trace(s"Received REQUEST $n from $from in state 'producing'")
      if (from eq out) {
        if (n > 0) {
          @tailrec def rec(nn: Int): State = {
            val elem = elems.next()
            recordElem(elem)
            ctx.run("⇢ " + elem)(out.onNext(elem))
            if (elems.hasNext) {
              if (nn > 1) rec(nn - 1)
              else stay()
            } else terminate(out)
          }
          rec(n)
        } else throw illegalState(s"Received illegal REQUEST $n from outport '$out'")
      } else throw illegalState(s"Received REQUEST $n from unexpected outport '$from' instead of outport '$out'")
    },

    cancel = from ⇒ {
      ctx.trace(s"Received CANCEL from $from in state 'producing'")
      fixtureState = TestFixture.State.Cancelled
      if (from eq out) cancelled(out)
      else throw illegalState(s"Received CANCEL from unexpected outport '$from' instead of outport '$out'")
    })

  def cancelled(out: Outport): State = state(
    request = (n, from) ⇒ {
      ctx.trace(s"Received REQUEST $n from $from in state 'cancelled'")
      if (from eq out) throw illegalState(s"Received REQUEST $n after CANCEL from outport '$out'")
      else throw illegalState(s"Received REQUEST $n from unexpected outport '$from' instead of outport '$out'")
    },

    cancel = from ⇒ {
      ctx.trace(s"Received CANCEL from $from in state 'cancelled'")
      if (from eq out) throw illegalState(s"Received double CANCEL from outport '$out'")
      else throw illegalState(s"Received CANCEL from unexpected outport '$from' instead of outport '$out'")
    })

  def completed(out: Outport): State = state(
    request = (n, from) ⇒ {
      ctx.trace(s"Received REQUEST $n from $from in state 'completed'")
      if (from eq out) stay()
      else throw illegalState(s"Received REQUEST $n from unexpected outport '$from' instead of outport '$out'")
    },

    cancel = from ⇒ {
      ctx.trace(s"Received CANCEL from $from in state 'completed'")
      if (from eq out) cancelled(out)
      else throw illegalState(s"Received CANCEL from unexpected outport '$from' instead of outport '$out'")
    })

  def errored(out: Outport): State = state(
    request = (n, from) ⇒ {
      ctx.trace(s"Received REQUEST $n from $from in state 'errored'")
      if (from eq out) stay()
      else throw illegalState(s"Received REQUEST $n from unexpected outport '$from' instead of outport '$out'")
    },

    cancel = from ⇒ {
      ctx.trace(s"Received CANCEL from $from in state 'errored'")
      if (from eq out) cancelled(out)
      else throw illegalState(s"Received CANCEL from unexpected outport '$from' instead of outport '$out'")
    })
}
