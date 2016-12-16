/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.drain

import scala.util.control.NonFatal
import swave.core.impl.stages.spout.SubSpoutStage
import swave.core.impl.{Inport, Outport}
import swave.core.macros.StageImplementation
import swave.core.impl.stages.DrainStage
import swave.core._

// format: OFF
@StageImplementation
private[core] final class LazyStartDrainStage[R](onStart: () => Drain[_, R], connectResult: R => Unit)
  extends DrainStage {

  def kind = Stage.Kind.Drain.LazyStart(onStart)

  connectInAndSealWith { in ⇒
    region.impl.registerForXStart(this)
    awaitingXStart(in)
  }

  def awaitingXStart(in: Inport) = state(
    xStart = () => {
      var funError: Throwable = null
      val innerDrain =
        try {
          val d = onStart()
          connectResult(d.result)
          d
        } catch { case NonFatal(e) => { funError = e; null } }
      if (funError eq null) {
        val sub = new SubSpoutStage(this)
        sub.subscribe()(innerDrain.outport)
        try {
          region.sealAndStart(sub)
          running(in, sub)
        } catch {
          case NonFatal(e) =>
            in.cancel()
            stop(e)
        }
      } else {
        in.cancel()
        stop(funError)
      }
    })

  def running(in: Inport, out: Outport) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),
    onNext = onNextF(out),
    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))
}
