/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inject

import scala.util.control.NonFatal
import swave.core.impl.stages.InOutStage
import swave.core.impl.stages.spout.SubSpoutStage
import swave.core.impl.{Inport, Outport, RunContext}
import swave.core.macros._
import swave.core.util._
import swave.core.{Split, Spout, Stage}

// format: OFF
@StageImplementation(fullInterceptions = true)
private[core] final class SplitStage(commandFor: AnyRef ⇒ Split.Command, eagerCancel: Boolean) extends InOutStage {

  def kind = Stage.Kind.InOut.Split(commandFor, eagerCancel)

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForXStart(this)
    running(ctx, in, out)
  }

  def running(ctx: RunContext, in: Inport, out: Outport) = {

    def awaitingXStart() = state(
      xStart = () => {
        in.request(1)
        noSubAwaitingElem(mainRemaining = 0)
      })

    /**
      * One element buffered, no sub-stream open.
      * Waiting for the next request from the main downstream.
      *
      * @param elem the buffered element
      * @param lastInSub true if `elem` is the last element in its sub
      */
    def noSubAwaitingMainDemand(elem: AnyRef, lastInSub: Boolean): State = state(
      request = (n, from) ⇒ {
        if (from eq out) awaitingSubDemand(emitNewSub(), elem, lastInSub, (n - 1).toLong) else stay()
      },

      cancel = from => if (from eq out) stopCancel(in) else stay(),
      onComplete = _ => noSubAwaitingMainDemandUpstreamGone(elem),
      onError = stopErrorF(out))

    /**
      * One element buffered, upstream already completed, no sub-stream open.
      * Waiting for the next request from the main downstream.
      *
      * @param elem the buffered element
      */
    def noSubAwaitingMainDemandUpstreamGone(elem: AnyRef): State = state(
      request = (n, from) ⇒ {
        if (from eq out) {
          val s = emitNewSub()
          s.xEvent(SubSpoutStage.EnableSubscriptionTimeout)
          awaitingSubDemandUpstreamGone(s, elem)
        } else stay()
      },

      cancel = from => if (from eq out) stopCancel(in) else stay())

    /**
      * No element buffered, no sub-stream open, one element requested from upstream.
      * Waiting for the next element from upstream.
      *
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def noSubAwaitingElem(mainRemaining: Long): State = state(
      request = (n, from) ⇒ if (from eq out) noSubAwaitingElem(mainRemaining ⊹ n) else stay(),
      cancel = from => if (from eq out) stopCancel(in) else stay(),

      onNext = (elem, _) ⇒ {
        var funError: Throwable = null
        val cmd = try commandFor(elem) catch { case NonFatal(e) => { funError = e; Split.Emit } }
        if (funError eq null) {
          cmd match {
            case Split.Emit | Split.CompleteEmit =>
              if (mainRemaining > 0) awaitingSubDemand(emitNewSub(), elem, lastInSub = false, mainRemaining - 1)
              else noSubAwaitingMainDemand(elem, lastInSub = false)
            case Split.Drop | Split.DropComplete =>
              in.request(1)
              stay()
            case Split.EmitComplete =>
              if (mainRemaining > 0) awaitingSubDemand(emitNewSub(), elem, lastInSub = true, mainRemaining - 1)
              else noSubAwaitingMainDemand(elem, lastInSub = true)
          }
        } else {
          in.cancel()
          stopError(funError, out)
        }
      },

      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    /**
      * One element buffered, sub-stream open.
      * Waiting for the next request from the sub-stream.
      *
      * @param sub           the currently open sub-stream
      * @param elem the buffered element waiting to be emitted to `sub`
      * @param lastInSub true if `elem` is the last element in `sub`
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingSubDemand(sub: SubSpoutStage, elem: AnyRef, lastInSub: Boolean, mainRemaining: Long): State = state(
      request = (n, from) ⇒ {
        if (from eq sub) {
          sub.onNext(elem)
          in.request(1)
          if (lastInSub) {
            sub.onComplete()
            noSubAwaitingElem(mainRemaining)
          } else awaitingElem(sub, subRemaining = n.toLong - 1, mainRemaining)
        } else if (from eq out) awaitingSubDemand(sub, elem, lastInSub, mainRemaining ⊹ n)
        else stay()
      },

      cancel = {
        case x if x eq sub =>
          if (eagerCancel) {
            out.onComplete()
            stopCancel(in)
          } else {
            in.request(1)
            noSubAwaitingElem(mainRemaining)
          }
        case x if x eq out =>
          sub.xEvent(SubSpoutStage.EnableSubscriptionTimeout)
          awaitingSubDemandDownstreamGone(sub, elem, lastInSub)
        case _ => stay()
      },

      onComplete = _ => {
        sub.xEvent(SubSpoutStage.EnableSubscriptionTimeout)
        awaitingSubDemandUpstreamGone(sub, elem)
      },
      onError = stopErrorSubAndMainF(sub))

    /**
      * One element buffered, sub-stream open, upstream already completed.
      * Waiting for the next request from the sub-stream.
      *
      * @param sub           the currently open sub-stream
      * @param elem  the buffered element waiting to be emitted to `sub`
      */
    def awaitingSubDemandUpstreamGone(sub: SubSpoutStage, elem: AnyRef): State = state(
      request = (n, from) ⇒ {
        if (from eq sub) {
          sub.onNext(elem)
          sub.onComplete()
          stopComplete(out)
        } else if (from eq out) awaitingSubDemandUpstreamGone(sub, elem)
        else stay()
      },

      cancel = {
        case x if x eq sub => stopComplete(out)
        case x if x eq out => awaitingSubDemandUpAndDownstreamGone(sub, elem)
        case _ => stay()
      })

    /**
      * One element buffered, sub-stream open, main downstream already cancelled.
      * Waiting for the next request from the sub-stream.
      *
      * @param sub     the currently open sub-stream
      * @param elem  the buffered element waiting to be emitted to `sub`
      * @param lastInSub true if `elem` is the last element in `sub`
      */
    def awaitingSubDemandDownstreamGone(sub: SubSpoutStage, elem: AnyRef, lastInSub: Boolean): State = state(
      request = (n, from) ⇒ {
        if (from eq sub) {
          sub.onNext(elem)
          if (lastInSub) {
            sub.onComplete()
            stopCancel(in)
          } else {
            in.request(1)
            awaitingElemDownstreamGone(sub, n.toLong - 1)
          }
        } else stay()
      },

      cancel = from => if (from eq sub) stopCancel(in) else stay(),
      onComplete = _ => awaitingSubDemandUpAndDownstreamGone(sub, elem),
      onError = stopErrorF(sub))

    /**
      * One element buffered, sub-stream open, upstream already completed, main downstream already cancelled.
      * Waiting for the final request from the sub-stream.
      *
      * @param sub the currently open sub-stream
      * @param elem  the buffered element waiting to be emitted to `sub`
      */
    def awaitingSubDemandUpAndDownstreamGone(sub: SubSpoutStage, elem: AnyRef): State = state(
      request = (n, from) ⇒ {
        if (from eq sub) {
          sub.onNext(elem)
          stopComplete(sub)
        } else stay()
      },

      cancel = from => if (from eq sub) stopCancel(in) else stay())

    /**
      * No element buffered, sub-stream open, one element requested from upstream.
      * Waiting for the next element from upstream.
      *
      * @param sub           the currently open sub-stream
      * @param subRemaining  number of elements already requested by sub-stream but not yet delivered, >= 0
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingElem(sub: SubSpoutStage, subRemaining: Long, mainRemaining: Long): State = state(
      request = (n, from) ⇒ {
        if (from eq sub) awaitingElem(sub, subRemaining ⊹ n, mainRemaining)
        else if (from eq out) awaitingElem(sub, subRemaining, mainRemaining ⊹ n)
        else stay()
      },

      cancel = {
        case x if x eq sub => noSubAwaitingElem(mainRemaining)
        case x if x eq out =>
          sub.xEvent(SubSpoutStage.EnableSubscriptionTimeout)
          awaitingElemDownstreamGone(sub, subRemaining)
        case _ => stay()
      },

      onNext = (elem, _) ⇒ {
        var funError: Throwable = null
        val cmd = try commandFor(elem) catch { case NonFatal(e) => { funError = e; Split.Emit } }
        if (funError eq null) {
          cmd match {
            case Split.Emit =>
              if (subRemaining > 0) {
                sub.onNext(elem)
                in.request(1)
                awaitingElem(sub, subRemaining - 1, mainRemaining)
              } else awaitingSubDemand(sub, elem, lastInSub = false, mainRemaining)
            case Split.Drop =>
              in.request(1)
              stay()
            case Split.EmitComplete =>
              if (subRemaining > 0) {
                sub.onNext(elem)
                sub.onComplete()
                in.request(1)
                noSubAwaitingElem(mainRemaining)
              } else awaitingSubDemand(sub, elem, lastInSub = true, mainRemaining)
            case Split.CompleteEmit =>
              sub.onComplete()
              if (mainRemaining > 0) awaitingSubDemand(emitNewSub(), elem, lastInSub = false, mainRemaining - 1)
              else noSubAwaitingMainDemand(elem, lastInSub = false)
            case Split.DropComplete =>
              sub.onComplete()
              in.request(1)
              noSubAwaitingElem(mainRemaining)
          }
        } else {
          in.cancel()
          sub.onError(funError)
          stopError(funError, out)
        }
      },

      onComplete = stopCompleteSubAndMainF(sub),
      onError = stopErrorSubAndMainF(sub))

    /**
      * No element buffered, sub-stream open, main downstream cancelled, one element requested from upstream.
      * Waiting for the next element from upstream.
      *
      * @param sub           the currently open sub-stream
      * @param subRemaining  number of elements already requested by sub-stream but not yet delivered, >= 0
      */
    def awaitingElemDownstreamGone(sub: SubSpoutStage, subRemaining: Long): State = state(
      request = (n, from) ⇒ if (from eq sub) awaitingElemDownstreamGone(sub, subRemaining ⊹ n) else stay(),
      cancel = from => if (from eq sub) stopCancel(in) else stay(),

      onNext = (elem, _) ⇒ {
        var funError: Throwable = null
        val cmd = try commandFor(elem) catch { case NonFatal(e) => { funError = e; Split.Emit } }
        if (funError eq null) {
          cmd match {
            case Split.Emit =>
              if (subRemaining > 0) {
                sub.onNext(elem)
                in.request(1)
                awaitingElemDownstreamGone(sub, subRemaining - 1)
              } else awaitingSubDemandDownstreamGone(sub, elem, lastInSub = false)
            case Split.Drop =>
              in.request(1)
              stay()
            case Split.EmitComplete =>
              if (subRemaining > 0) {
                sub.onNext(elem)
                sub.onComplete()
                stopCancel(in)
              } else awaitingSubDemandDownstreamGone(sub, elem, lastInSub = true)
            case Split.CompleteEmit | Split.DropComplete =>
              sub.onComplete()
              stopCancel(in)
          }
        } else {
          in.cancel()
          stopError(funError, sub)
        }
      },

      onComplete = stopCompleteSubAndMainF(sub),
      onError = stopErrorSubAndMainF(sub))

    ///////////////////////// helpers //////////////////////////

    def emitNewSub() = {
      val s = new SubSpoutStage(ctx, this)
      out.onNext(new Spout(s).asInstanceOf[AnyRef])
      s
    }

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
