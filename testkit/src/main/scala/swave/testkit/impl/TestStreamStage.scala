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

import scala.annotation.tailrec
import swave.core.PipeElem
import swave.core.impl.Outport
import swave.testkit.TestFixture
import swave.core.impl.stages.source.SourceStage

private[testkit] final class TestStreamStage(
    val id: Int,
    val elemsIterable: Iterable[AnyRef],
    val termination: Option[Throwable],
    ctx: TestContext) extends SourceStage with TestStage with PipeElem.Source.Test {

  private[this] val elems: Iterator[AnyRef] = elemsIterable.iterator

  def pipeElemType: String = "Stream.test"
  def pipeElemParams: List[Any] = id :: Nil

  override def toString: String = "Input  " + id

  def formatLong =
    s"""|Input   : id = $id, state = $fixtureState / $stateName
        |script  : size = $scriptedSize, elem = [${elemsIterable.mkString(", ")}], termination = $termination
        |produced: size = $resultSize, elems = [${result.mkString(", ")}]""".stripMargin

  def scriptedSize: Int = elemsIterable.size

  // format: OFF

  initialState {
    state(name = "initialState",

      subscribe = out ⇒ {
        ctx.trace(s"Received SUBSCRIBE from $out in 'initialState'")
        ctx.trace("⇢ ONSUBSCRIBE")
        setOutputElem(out.pipeElem)
        out.onSubscribe()
        ready(out)
      })
  }

  private def ready(out: Outport): State =
    fullState(name = "ready",

      subscribe = doubleSubscribe,

      start = startContext ⇒ {
        ctx.trace("Received START in 'ready'")
        configureFrom(startContext.env)
        ctx.trace("⇢ START")
        out.start(startContext)
        if (elems.hasNext) {
          fixtureState = TestFixture.State.Running
          producing(out)
        } else terminate(out)
      })

  private def terminate(out: Outport) =
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

  private def producing(out: Outport): State =
    state(name = "producing",

      request = (n, from) ⇒ {
        ctx.trace(s"Received REQUEST $n from $from in state 'producing'")
        if (from eq out) {
          if (n > 0) {
            @tailrec def rec(n: Int): State = {
              val elem = elems.next()
              recordElem(elem)
              ctx.run("⇢ " + elem)(out.onNext(elem))
              if (elems.hasNext) {
                if (n > 1) rec(n - 1)
                else stay()
              } else terminate(out)
            }
            rec(n)
          } else illegalState(s"Received illegal REQUEST $n from outport '$out' in $this")
        } else illegalState(s"Received REQUEST $n from unexpected outport '$from' instead of outport '$out' in $this")
      },

      cancel = from ⇒ {
        ctx.trace(s"Received CANCEL from $from in state 'producing'")
        fixtureState = TestFixture.State.Cancelled
        if (from eq out) cancelled(out)
        else illegalState(s"Received CANCEL from unexpected outport '$from' instead of outport '$out' in $this")
      })

  private def cancelled(out: Outport): State =
    state(name = "cancelled",

      request = (n, from) ⇒ {
        ctx.trace(s"Received REQUEST $n from $from in state 'cancelled'")
        if (from eq out) illegalState(s"Received REQUEST $n after CANCEL from outport '$out' in $this")
        else illegalState(s"Received REQUEST $n from unexpected outport '$from' instead of outport '$out' in $this")
      },

      cancel = from ⇒ {
        ctx.trace(s"Received CANCEL from $from in state 'cancelled'")
        if (from eq out) illegalState(s"Received double CANCEL from outport '$out' in $this")
        else illegalState(s"Received CANCEL from unexpected outport '$from' instead of outport '$out' in $this")
      })

  private def completed(out: Outport): State =
    state(name = "completed",

      request = (n, from) ⇒ {
        ctx.trace(s"Received REQUEST $n from $from in state 'completed'")
        if (from eq out) stay()
        else illegalState(s"Received REQUEST $n from unexpected outport '$from' instead of outport '$out' in $this")
      },

      cancel = from ⇒ {
        ctx.trace(s"Received CANCEL from $from in state 'completed'")
        if (from eq out) cancelled(out)
        else illegalState(s"Received CANCEL from unexpected outport '$from' instead of outport '$out' in $this")
      })

  private def errored(out: Outport): State =
    state(name = "errored",

      request = (n, from) ⇒ {
        ctx.trace(s"Received REQUEST $n from $from in state 'errored'")
        if (from eq out) stay()
        else illegalState(s"Received REQUEST $n from unexpected outport '$from' instead of outport '$out' in $this")
      },

      cancel = from ⇒ {
        ctx.trace(s"Received CANCEL from $from in state 'errored'")
        if (from eq out) cancelled(out)
        else illegalState(s"Received CANCEL from unexpected outport '$from' instead of outport '$out' in $this")
      })
}