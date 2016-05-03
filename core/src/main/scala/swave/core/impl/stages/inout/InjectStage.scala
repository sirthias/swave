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

import scala.annotation.tailrec
import swave.core.{ PipeElem, Stream }
import swave.core.impl.stages.Stage
import swave.core.impl.stages.source.SubSourceStage
import swave.core.impl.{ StartContext, Outport, Inport }
import swave.core.util._

// format: OFF
private[core] final class InjectStage extends InOutStage with PipeElem.InOut.Inject { stage =>

  def pipeElemType: String = "inject"
  def pipeElemParams: List[Any] = Nil

  private[this] var buffer: RingBuffer[AnyRef] = _

  connectInOutAndStartWith { (ctx, in, out) ⇒
    buffer = new RingBuffer[AnyRef](roundUpToNextPowerOf2(ctx.env.settings.maxBatchSize))
    in.request(buffer.capacity.toLong)
    ctx.registerForCleanupExtra(this)
    running(ctx, in, out)
  }

  def running(ctx: StartContext, in: Inport, out: Outport) = {

    /**
     * Buffer non-empty, no sub-stream open.
     * Waiting for the next request from the main downstream.
     *
     * @param pending number of elements already requested from upstream but not yet received (> 0)
     *                or 0, if the buffer is full
     */
    def noSubAwaitingMainDemand(pending: Int): State =
      state(name = "noSubAwaitingMainDemand",
        request = (n, from) ⇒ if (from eq out) awaitingSubDemand(emitNewSub(), pending, (n - 1).toLong) else stay(),
        cancel = from => if (from eq out) stopCancel(in) else stay(),

        onNext = (elem, _) ⇒ {
          requireState(buffer.write(elem))
          noSubAwaitingMainDemand(pendingAfterReceive(pending))
        },

        onComplete = _ => noSubAwaitingMainDemandUpstreamGone(),
        onError = stopErrorF(out),
        extra = ignoreCleanup)

    /**
     * Buffer non-empty, upstream already completed, no sub-stream open.
     * Waiting for the next request from the main downstream.
     */
    def noSubAwaitingMainDemandUpstreamGone(): State =
      state(name = "noSubAwaitingMainDemandUpstreamGone",
        request = (n, from) ⇒ if (from eq out) awaitingSubDemandUpstreamGone(emitNewSub(), (n - 1).toLong) else stay(),
        cancel = from => if (from eq out) stopCancel(in) else stay())

    /**
     * Buffer empty, no sub-stream open, demand signalled to upstream.
     * Waiting for the next element from upstream.
     *
     * @param pending       number of elements already requested from upstream but not yet received, > 0
     * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
     */
    def noSubAwaitingElem(pending: Int, mainRemaining: Long): State =
      state(name = "noSubAwaitingElem",
        request = (n, from) ⇒ if (from eq out) noSubAwaitingElem(pending, mainRemaining ⊹ n) else stay(),
        cancel = from => if (from eq out) stopCancel(in) else stay(),

        onNext = (elem, _) ⇒ {
          requireState(buffer.write(elem))
          if (mainRemaining > 0) awaitingSubDemand(emitNewSub(), pendingAfterReceive(pending), mainRemaining - 1)
          else noSubAwaitingMainDemand(pendingAfterReceive(pending))
        },

        onComplete = stopCompleteF(out),
        onError = stopErrorF(out),
        extra = ignoreCleanup)

    /**
     * Buffer non-empty, sub-stream open.
     * Waiting for the next request from the sub-stream.
     *
     * @param sub           the currently open sub-stream
     * @param pending       number of elements already requested from upstream but not yet received (> 0),
     *                      or 0, if the buffer is full
     * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
     */
    def awaitingSubDemand(sub: SubSourceStage, pending: Int, mainRemaining: Long): State =
      state(name = "awaitingSubDemand",

        request = (n, from) ⇒ {
          @tailrec def rec(n: Int): State =
            if (buffer.nonEmpty) {
              if (n > 0) {
                sub.onNext(buffer.unsafeRead())
                rec(n - 1)
              } else awaitingSubDemand(sub, pendingAfterBufferRead(pending), mainRemaining)
            } else awaitingElem(sub, pendingAfterBufferRead(pending), subRemaining = n.toLong, mainRemaining)

          if (from eq sub) rec(n)
          else if (from eq out) awaitingSubDemand(sub, pending, mainRemaining ⊹ n)
          else stay()
        },

        cancel = from => {
          if (from eq sub) {
            if (mainRemaining > 0) awaitingSubDemand(emitNewSub(), pending, mainRemaining - 1)
            else noSubAwaitingMainDemand(pending)
          } else if (from eq out) awaitingSubDemandDownstreamGone(sub, pending)
          else stay()
        },

        onNext = (elem, _) ⇒ {
          requireState(buffer.write(elem))
          awaitingSubDemand(sub, pendingAfterReceive(pending), mainRemaining)
        },

        onComplete = _ => awaitingSubDemandUpstreamGone(sub, mainRemaining),
        onError = stopErrorSubAndMainF(sub),
        extra = ignoreCleanup)

    /**
     * Buffer non-empty, sub-stream open, upstream already completed.
     * Waiting for the next request from the sub-stream.
     *
     * @param sub           the currently open sub-stream
     * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
     */
    def awaitingSubDemandUpstreamGone(sub: SubSourceStage, mainRemaining: Long): State =
      state(name = "awaitingSubDemandUpstreamGone",

        request = (n, from) ⇒ {
          @tailrec def rec(n: Int): State =
            if (buffer.nonEmpty) {
              if (n > 0) {
                sub.onNext(buffer.unsafeRead())
                rec(n - 1)
              } else awaitingSubDemandUpstreamGone(sub, mainRemaining)
            } else {
              sub.onComplete()
              stopComplete(out)
            }

          if (from eq sub) rec(n)
          else if (from eq out) awaitingSubDemandUpstreamGone(sub, mainRemaining ⊹ n)
          else stay()
        },

        cancel = from => {
          if (from eq sub) {
            if (mainRemaining > 0) awaitingSubDemandUpstreamGone(emitNewSub(), mainRemaining - 1)
            else noSubAwaitingMainDemandUpstreamGone()
          } else if (from eq out) awaitingSubDemandUpAndDownstreamGone(sub)
          else stay()
        },

        extra = ignoreCleanup)

    /**
     * Buffer non-empty, sub-stream open, main downstream already cancelled.
     * Waiting for the next request from the sub-stream.
     *
     * @param sub     the currently open sub-stream
     * @param pending number of elements already requested from upstream but not yet received (> 0),
     *                or 0, if the buffer is full
     */
    def awaitingSubDemandDownstreamGone(sub: SubSourceStage, pending: Int): State =
      state(name = "awaitingSubDemandDownstreamGone",

        request = (n, from) ⇒ {
          @tailrec def rec(n: Int): State =
            if (buffer.nonEmpty) {
              if (n > 0) {
                sub.onNext(buffer.unsafeRead())
                rec(n - 1)
              } else awaitingSubDemandDownstreamGone(sub, pendingAfterBufferRead(pending))
            } else awaitingElemDownstreamGone(sub, pendingAfterBufferRead(pending), subRemaining = n.toLong)

          if (from eq sub) rec(n) else stay()
        },

        cancel = from => if (from eq sub) stopCancel(in) else stay(),

        onNext = (elem, _) ⇒ {
          requireState(buffer.write(elem))
          awaitingSubDemandDownstreamGone(sub, pendingAfterReceive(pending))
        },

        onComplete = _ => awaitingSubDemandUpAndDownstreamGone(sub),
        onError = stopErrorF(sub),
        extra = cleanup(sub))

    /**
     * Buffer non-empty, sub-stream open, upstream already completed, main downstream already cancelled.
     * Waiting for the next request from the sub-stream.
     *
     * @param sub the currently open sub-stream
     */
    def awaitingSubDemandUpAndDownstreamGone(sub: SubSourceStage): State =
      state(name = "awaitingSubDemandUpAndDownstreamGone",

        request = (n, from) ⇒ {
          @tailrec def rec(n: Int): State =
            if (buffer.nonEmpty) {
              if (n > 0) {
                sub.onNext(buffer.unsafeRead())
                rec(n - 1)
              } else awaitingSubDemandUpAndDownstreamGone(sub)
            } else stopComplete(sub)

          if (from eq sub) rec(n) else stay()
        },

        cancel = from => if (from eq sub) stopCancel(in) else stay(),
        extra = cleanup(sub))

    /**
     * Buffer empty, sub-stream open, demand signalled to upstream.
     * Waiting for the next element from upstream.
     *
     * @param sub           the currently open sub-stream
     * @param pending       number of elements already requested from upstream but not yet received, > 0
     * @param subRemaining  number of elements already requested by sub-stream but not yet delivered, >= 0
     * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
     */
    def awaitingElem(sub: SubSourceStage, pending: Int, subRemaining: Long, mainRemaining: Long): State =
      state(name = "awaitingElem",

        request = (n, from) ⇒ {
          if (from eq sub) awaitingElem(sub, pending, subRemaining ⊹ n, mainRemaining)
          else if (from eq out) awaitingElem(sub, pending, subRemaining, mainRemaining ⊹ n)
          else stay()
        },

        cancel = from => {
          if (from eq sub) noSubAwaitingElem(pending, mainRemaining)
          else if (from eq out) awaitingElemDownstreamGone(sub, pending, subRemaining)
          else stay()
        },

        onNext = (elem, _) ⇒ {
          if (subRemaining > 0) {
            sub.onNext(elem)
            awaitingElem(sub, pendingAfterReceive(pending), subRemaining - 1, mainRemaining)
          } else {
            requireState(buffer.write(elem))
            awaitingSubDemand(sub, pendingAfterReceive(pending), mainRemaining)
          }
        },

        onComplete = stopCompleteSubAndMainF(sub),
        onError = stopErrorSubAndMainF(sub),
        extra = cleanup(sub))

    /**
     * Buffer empty, sub-stream open, main downstream cancelled, demand signalled to upstream.
     * Waiting for the next element from upstream.
     *
     * @param sub           the currently open sub-stream
     * @param pending       number of elements already requested from upstream but not yet received, > 0
     * @param subRemaining  number of elements already requested by sub-stream but not yet delivered, >= 0
     */
    def awaitingElemDownstreamGone(sub: SubSourceStage, pending: Int, subRemaining: Long): State =
      state(name = "awaitingElemDownstreamGone",
        request = (n, from) ⇒ if (from eq sub) awaitingElemDownstreamGone(sub, pending, subRemaining ⊹ n) else stay(),
        cancel = from => if (from eq sub) stopCancel(in) else stay(),

        onNext = (elem, _) ⇒ {
          if (subRemaining > 0) {
            sub.onNext(elem)
            awaitingElemDownstreamGone(sub, pendingAfterReceive(pending), subRemaining - 1)
          } else {
            requireState(buffer.write(elem))
            awaitingSubDemandDownstreamGone(sub, pendingAfterReceive(pending))
          }
        },

        onComplete = stopCompleteSubAndMainF(sub),
        onError = stopErrorSubAndMainF(sub),
        extra = cleanup(sub))

    ///////////////////////// helpers //////////////////////////

    def emitNewSub() = {
      val sub = new SubSourceStage(ctx, this)
      out.onNext(new Stream(sub).asInstanceOf[AnyRef])
      sub
    }

    def pendingAfterReceive(pending: Int) =
      if (pending == 1) {
        val avail = buffer.available
        if (avail > 0) in.request(avail.toLong)
        avail
      } else pending - 1

    def pendingAfterBufferRead(pending: Int) =
      if (pending == 0) {
        val avail = buffer.available
        if (avail > 0) in.request(avail.toLong)
        avail
      } else pending

    def stopCompleteSubAndMainF(sub: SubSourceStage)(in: Inport) = {
      sub.onComplete()
      stopComplete(out)
    }

    def stopErrorSubAndMainF(sub: SubSourceStage)(e: Throwable, in: Inport) = {
      sub.onError(e)
      stopError(e, out)
    }

    def cleanup(sub: SubSourceStage): Stage.ExtraSignalHandler = {
      case Stage.Cleanup =>
        // in a synchronous run: if we are still in this state after the stream has run in its entirety
        // the sub we are awaiting demand from was dropped
        sub.assertNotRunning()
        cancel()(from = sub)
    }

    def ignoreCleanup: Stage.ExtraSignalHandler = PartialFunction.empty

    noSubAwaitingElem(buffer.capacity, mainRemaining = 0)
  }
}