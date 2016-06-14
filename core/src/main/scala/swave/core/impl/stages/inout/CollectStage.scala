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
private[core] final class CollectStage(pf: PartialFunction[AnyRef, AnyRef]) extends InOutStage with PipeElem.InOut.Collect {

  def pipeElemType: String = "collect"
  def pipeElemParams: List[Any] = pf :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    val mismatchF: AnyRef => this.type = { elem =>
      in.request(1)
      this // we use `this` as a special result instance signalling the mismatch of a single collect
    }
    running(in, out, mismatchF)
  }

  def running(in: Inport, out: Outport, mismatchFun: AnyRef => this.type) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      try {
        val collected = pf.applyOrElse(elem, mismatchFun)
        if (collected ne this) out.onNext(collected)
        stay()
      } catch { case NonFatal(e) => { in.cancel(); stopError(e, out) } }
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}

