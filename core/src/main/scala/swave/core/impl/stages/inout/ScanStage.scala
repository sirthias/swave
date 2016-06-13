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

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.PipeElem
import swave.core.impl.{ Inport, Outport }
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class ScanStage(zero: AnyRef, f: (AnyRef, AnyRef) ⇒ AnyRef)
  extends InOutStage with PipeElem.InOut.Scan {

  def pipeElemType: String = "scan"
  def pipeElemParams: List[Any] = zero :: f :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ awaitingDemand(in, out) }

  /**
   * @param in  the active upstream
   * @param out the active downstream
   */
  def awaitingDemand(in: Inport, out: Outport): State = state(
    request = (n, _) ⇒ {
      out.onNext(zero)
      if (n > 1) in.request((n - 1).toLong)
      running(in, out, zero)
    },

    cancel = stopCancelF(in),
    onComplete = _ ⇒ drainingZero(out),
    onError = stopErrorF(out))

  /**
   * @param in        the active upstream
   * @param out       the active downstream
   * @param last      the last value produced
   */
  def running(in: Inport, out: Outport, last: AnyRef): State = state(
    request = (n, _) ⇒ {
      in.request(n.toLong)
      stay()
    },

    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      try {
        val next = f(last, elem)
        out.onNext(next)
        running(in, out, next)
      } catch { case NonFatal(e) => { in.cancel(); stopError(e, out) } }
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))

  /**
   * Upstream completed without having produced any element, downstream active, awaiting first request.
   *
   * @param out  the active downstream
   */
  def drainingZero(out: Outport) = state(
    request = (_, _) ⇒ {
      out.onNext(zero)
      stopComplete(out)
    },

    cancel = stopF)
}

