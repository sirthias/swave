/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inject

import scala.annotation.tailrec
import swave.core.impl.stages.InOutStage
import swave.core.impl.stages.spout.SubSpoutStage
import swave.core.impl.util.RingBuffer
import swave.core.impl.{Inport, Outport, RunSupport}
import swave.core.macros._
import swave.core.util._
import swave.core.{Spout, Stage}

// TODO: reduce buffer to one single element, like in the SplitStage

// format: OFF
@StageImplementation(fullInterceptions = true)
private[core] final class InjectSequentialStage extends InOutStage with RunSupport.RunContextAccess { stage =>

  def kind = Stage.Kind.InOut.Inject

  private[this] var buffer: RingBuffer[AnyRef] = _

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForXStart(this)
    ctx.registerForRunContextAccess(this)
    running(in, out)
  }

  def running(in: Inport, out: Outport) = {

    def awaitingXStart() = state(
      xStart = () => {
        buffer = new RingBuffer[AnyRef](roundUpToPowerOf2(runContext.env.settings.maxBatchSize))
        in.request(buffer.capacity.toLong)
        noSubAwaitingElem(buffer.capacity, mainRemaining = 0)
      })

    /**
      * Buffer non-empty, no sub-stream open.
      * Waiting for the next request from the main downstream.
      *
      * @param pending number of elements already requested from upstream but not yet received (> 0)
      *                or 0, if the buffer is full
      */
    def noSubAwaitingMainDemand(pending: Int): State = state(
      request = (n, from) ⇒ if (from eq out) awaitingSubDemand(emitNewSub(), pending, (n - 1).toLong) else stay(),
      cancel = from => if (from eq out) stopCancel(in) else stay(),

      onNext = (elem, _) ⇒ {
        requireState(buffer.write(elem))
        noSubAwaitingMainDemand(pendingAfterReceive(pending))
      },

      onComplete = _ => noSubAwaitingMainDemandUpstreamGone(),
      onError = stopErrorF(out))

    /**
      * Buffer non-empty, upstream already completed, no sub-stream open.
      * Waiting for the next request from the main downstream.
      */
    def noSubAwaitingMainDemandUpstreamGone(): State = state(
      request = (n, from) ⇒ {
        if (from eq out) {
          val s = emitNewSub()
          s.xEvent(SubSpoutStage.EnableSubscriptionTimeout)
          awaitingSubDemandUpstreamGone(s, (n - 1).toLong)
        } else stay()
      },

      cancel = from => if (from eq out) stopCancel(in) else stay())

    /**
      * Buffer empty, no sub-stream open, demand signalled to upstream.
      * Waiting for the next element from upstream.
      *
      * @param pending       number of elements already requested from upstream but not yet received, > 0
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def noSubAwaitingElem(pending: Int, mainRemaining: Long): State = state(
      request = (n, from) ⇒ if (from eq out) noSubAwaitingElem(pending, mainRemaining ⊹ n) else stay(),
      cancel = from => if (from eq out) stopCancel(in) else stay(),

      onNext = (elem, _) ⇒ {
        requireState(buffer.write(elem))
        if (mainRemaining > 0) awaitingSubDemand(emitNewSub(), pendingAfterReceive(pending), mainRemaining - 1)
        else noSubAwaitingMainDemand(pendingAfterReceive(pending))
      },

      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    /**
      * Buffer non-empty, sub-stream open.
      * Waiting for the next request from the sub-stream.
      *
      * @param sub           the currently open sub-stream
      * @param pending       number of elements already requested from upstream but not yet received (> 0),
      *                      or 0, if the buffer is full
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingSubDemand(sub: SubSpoutStage, pending: Int, mainRemaining: Long): State = state(
      request = (n, from) ⇒ {
        @tailrec def rec(nn: Int): State =
          if (buffer.nonEmpty) {
            if (nn > 0) {
              sub.onNext(buffer.unsafeRead())
              rec(nn - 1)
            } else awaitingSubDemand(sub, pendingAfterBufferRead(pending), mainRemaining)
          } else awaitingElem(sub, pendingAfterBufferRead(pending), subRemaining = nn.toLong, mainRemaining)

        if (from eq sub) rec(n)
        else if (from eq out) awaitingSubDemand(sub, pending, mainRemaining ⊹ n)
        else stay()
      },

      cancel = {
        case x if x eq sub =>
          if (mainRemaining > 0) awaitingSubDemand(emitNewSub(), pending, mainRemaining - 1)
          else noSubAwaitingMainDemand(pending)
        case x if x eq out =>
          sub.xEvent(SubSpoutStage.EnableSubscriptionTimeout)
          awaitingSubDemandDownstreamGone(sub, pending)
        case _ => stay()
      },

      onNext = (elem, _) ⇒ {
        requireState(buffer.write(elem))
        awaitingSubDemand(sub, pendingAfterReceive(pending), mainRemaining)
      },

      onComplete = _ => {
        sub.xEvent(SubSpoutStage.EnableSubscriptionTimeout)
        awaitingSubDemandUpstreamGone(sub, mainRemaining)
      },
      onError = stopErrorSubAndMainF(sub))

    /**
      * Buffer non-empty, sub-stream open, upstream already completed.
      * Waiting for the next request from the sub-stream.
      *
      * @param sub           the currently open sub-stream
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingSubDemandUpstreamGone(sub: SubSpoutStage, mainRemaining: Long): State = state(
      request = (n, from) ⇒ {
        @tailrec def rec(nn: Int): State =
          if (buffer.nonEmpty) {
            if (nn > 0) {
              sub.onNext(buffer.unsafeRead())
              rec(nn - 1)
            } else awaitingSubDemandUpstreamGone(sub, mainRemaining)
          } else {
            sub.onComplete()
            stopComplete(out)
          }

        if (from eq sub) rec(n)
        else if (from eq out) awaitingSubDemandUpstreamGone(sub, mainRemaining ⊹ n)
        else stay()
      },

      cancel = {
        case x if x eq sub =>
          if (mainRemaining > 0) awaitingSubDemandUpstreamGone(emitNewSub(), mainRemaining - 1)
          else noSubAwaitingMainDemandUpstreamGone()
        case x if x eq out => awaitingSubDemandUpAndDownstreamGone(sub)
        case _ => stay()
      })

    /**
      * Buffer non-empty, sub-stream open, main downstream already cancelled.
      * Waiting for the next request from the sub-stream.
      *
      * @param sub     the currently open sub-stream
      * @param pending number of elements already requested from upstream but not yet received (> 0),
      *                or 0, if the buffer is full
      */
    def awaitingSubDemandDownstreamGone(sub: SubSpoutStage, pending: Int): State = state(
      request = (n, from) ⇒ {
        @tailrec def rec(nn: Int): State =
          if (buffer.nonEmpty) {
            if (nn > 0) {
              sub.onNext(buffer.unsafeRead())
              rec(nn - 1)
            } else awaitingSubDemandDownstreamGone(sub, pendingAfterBufferRead(pending))
          } else awaitingElemDownstreamGone(sub, pendingAfterBufferRead(pending), subRemaining = nn.toLong)

        if (from eq sub) rec(n) else stay()
      },

      cancel = from => if (from eq sub) stopCancel(in) else stay(),

      onNext = (elem, _) ⇒ {
        requireState(buffer.write(elem))
        awaitingSubDemandDownstreamGone(sub, pendingAfterReceive(pending))
      },

      onComplete = _ => awaitingSubDemandUpAndDownstreamGone(sub),
      onError = stopErrorF(sub))

    /**
      * Buffer non-empty, sub-stream open, upstream already completed, main downstream already cancelled.
      * Waiting for the next request from the sub-stream.
      *
      * @param sub the currently open sub-stream
      */
    def awaitingSubDemandUpAndDownstreamGone(sub: SubSpoutStage): State = state(
      request = (n, from) ⇒ {
        @tailrec def rec(nn: Int): State =
          if (buffer.nonEmpty) {
            if (nn > 0) {
              sub.onNext(buffer.unsafeRead())
              rec(nn - 1)
            } else awaitingSubDemandUpAndDownstreamGone(sub)
          } else stopComplete(sub)

        if (from eq sub) rec(n) else stay()
      },

      cancel = from => if (from eq sub) stopCancel(in) else stay())

    /**
      * Buffer empty, sub-stream open, demand signalled to upstream.
      * Waiting for the next element from upstream.
      *
      * @param sub           the currently open sub-stream
      * @param pending       number of elements already requested from upstream but not yet received, > 0
      * @param subRemaining  number of elements already requested by sub-stream but not yet delivered, >= 0
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingElem(sub: SubSpoutStage, pending: Int, subRemaining: Long, mainRemaining: Long): State = state(
      request = (n, from) ⇒ {
        if (from eq sub) awaitingElem(sub, pending, subRemaining ⊹ n, mainRemaining)
        else if (from eq out) awaitingElem(sub, pending, subRemaining, mainRemaining ⊹ n)
        else stay()
      },

      cancel = {
        case x if x eq sub => noSubAwaitingElem(pending, mainRemaining)
        case x if x eq out =>
          sub.xEvent(SubSpoutStage.EnableSubscriptionTimeout)
          awaitingElemDownstreamGone(sub, pending, subRemaining)
        case _ => stay()
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
      onError = stopErrorSubAndMainF(sub))

    /**
      * Buffer empty, sub-stream open, main downstream cancelled, demand signalled to upstream.
      * Waiting for the next element from upstream.
      *
      * @param sub           the currently open sub-stream
      * @param pending       number of elements already requested from upstream but not yet received, > 0
      * @param subRemaining  number of elements already requested by sub-stream but not yet delivered, >= 0
      */
    def awaitingElemDownstreamGone(sub: SubSpoutStage, pending: Int, subRemaining: Long): State = state(
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
      onError = stopErrorSubAndMainF(sub))

    ///////////////////////// helpers //////////////////////////

    def emitNewSub() = {
      val s = new SubSpoutStage(runContext, this)
      out.onNext(new Spout(s).asInstanceOf[AnyRef])
      s
    }

    def pendingAfterReceive(pend: Int) =
      if (pend == 1) {
        val avail = buffer.available
        if (avail > 0) in.request(avail.toLong)
        avail
      } else pend - 1

    def pendingAfterBufferRead(pend: Int) =
      if (pend == 0) {
        val avail = buffer.available
        if (avail > 0) in.request(avail.toLong)
        avail
      } else pend

    def stopCompleteSubAndMainF(s: SubSpoutStage)(i: Inport): State = {
      s.onComplete()
      stopComplete(out)
    }

    def stopErrorSubAndMainF(s: SubSpoutStage)(e: Throwable, i: Inport): State = {
      s.onError(e)
      stopError(e, out)
    }

    awaitingXStart()
  }
}
