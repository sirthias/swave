/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.collection.mutable
import swave.core.impl.{Inport, Outport}
import swave.core.PipeElem
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class SlidingStage(windowSize: Int, emitSingleEmpty: Boolean, builder: mutable.Builder[Any, AnyRef]) extends InOutStage
  with PipeElem.InOut.Sliding {

  requireArg(windowSize > 0, "`windowSize` must be > 0")

  def pipeElemType: String = "sliding"
  def pipeElemParams: List[Any] = windowSize :: emitSingleEmpty :: Nil

  var buffer = new RingBuffer[Any](roundUpToPowerOf2(windowSize))

  /**
    * Helper method: use current buffer content and builder to
    * construct one result collection
    */
  def buildFromBuffer() : AnyRef = {
    builder.clear()
    buffer.foreach { e => builder += e }
    builder.result()
  }

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport): State = {

    /**
      * Waiting for a request from downstream.
      */
    def awaitingDemand(count : Int, hasEmittedAtLeastOne: Boolean) = state(
      request = (n, _) ⇒ {
        in.request(count.toLong)
        collecting(n.toLong, hasEmittedAtLeastOne, firstElem = true)
      },

      cancel = stopCancelF(in),
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    /**
      * Gathering up the elements for the next group.
      *
      * @param remaining number of elements already requested by downstream but not yet delivered, > 0
      * @param firstElem true if we are still awaiting the very first element from upstream
      */
    def collecting(remaining: Long, hasEmittedAtLeastOne: Boolean, firstElem: Boolean): State = state(
      request = (n, _) ⇒ collecting(remaining ⊹ n, false, firstElem),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        buffer.write(elem)

        // if we have collected enough items to emit
        if (buffer.size == windowSize) {
          val group = buildFromBuffer()
          buffer.unsafeDropHead()
          out.onNext(group)

          if (remaining > 1) {
            in.request(1)
            collecting(remaining - 1, true, firstElem = false)
          } else awaitingDemand(1, true)
        } else collecting(remaining, hasEmittedAtLeastOne, firstElem = false)
      },

      onComplete = x ⇒ {
        // if we have not emitted anything so far, but have unemitted elems in the buffer,
        // or if this should emit empty...
        if (!hasEmittedAtLeastOne && buffer.size > 0 || firstElem && emitSingleEmpty) {
          out.onNext(buildFromBuffer())
        }
        builder.clear() // don't hold on to elements
        buffer.softClear()  // and clear buffer
        stopComplete(out)
      },

      onError = stopErrorF(out))

    awaitingDemand(windowSize, false)
  }
}
