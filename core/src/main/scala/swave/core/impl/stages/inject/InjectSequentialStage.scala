/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inject

import swave.core.impl.stages.InOutStage
import swave.core.impl.stages.spout.SubSpoutStage
import swave.core.impl.{Inport, Outport}
import swave.core.macros._
import swave.core.util._
import swave.core.{Spout, Stage}

// format: OFF
@StageImplementation(fullInterceptions = true)
private[core] final class InjectSequentialStage extends InOutStage { stage =>

  def kind = Stage.Kind.Inject.Sequential

  connectInOutAndSealWith { (in, out) ⇒
    region.impl.registerForXStart(this)
    running(in, out)
  }

  def running(in: Inport, out: Outport) = {

    def awaitingXStart() = state(
      xStart = () => {
        in.request(1)
        noSubAwaitingElem(0)
      })

    /**
      * One element buffered, no sub-stream open.
      * Waiting for the next request from the main downstream.
      *
      * @param elem the buffered element
      */
    def noSubAwaitingMainDemand(elem: AnyRef): State = state(
      request = (n, from) ⇒ {
        if (from eq out) awaitingSubDemand(emitNewSub(), elem, (n - 1).toLong) else stay()
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
          s.xEvent(SubSpoutStage.EnableSubStreamStartTimeout)
          awaitingSubDemandUpstreamGone(s, elem, (n - 1).toLong)
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
        if (mainRemaining > 0) awaitingSubDemand(emitNewSub(), elem, mainRemaining - 1)
        else noSubAwaitingMainDemand(elem)
      },

      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    /**
      * One element buffered, sub-stream open.
      * Waiting for the next request from the sub-stream.
      *
      * @param sub           the currently open sub-stream
      * @param elem          the buffered element waiting to be emitted to `sub`
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingSubDemand(sub: SubSpoutStage, elem: AnyRef, mainRemaining: Long): State = state(
      request = (n, from) ⇒ {
        if (from eq sub) {
          sub.onNext(elem)
          in.request(1)
          awaitingElem(sub, subRemaining = n.toLong - 1, mainRemaining)
        } else if (from eq out) awaitingSubDemand(sub, elem, mainRemaining ⊹ n)
        else stay()
      },

      cancel = {
        case x if x eq sub =>
          if (mainRemaining > 0) awaitingSubDemand(emitNewSub(), elem, mainRemaining - 1)
          else noSubAwaitingMainDemand(elem)
        case x if x eq out =>
          sub.xEvent(SubSpoutStage.EnableSubStreamStartTimeout)
          awaitingSubDemandDownstreamGone(sub, elem)
        case _ => stay()
      },

      onComplete = _ => {
        sub.xEvent(SubSpoutStage.EnableSubStreamStartTimeout)
        awaitingSubDemandUpstreamGone(sub, elem, mainRemaining)
      },
      onError = stopErrorSubAndMainF(sub))

    /**
      * One element buffered, sub-stream open, upstream already completed.
      * Waiting for the next request from the sub-stream.
      *
      * @param sub           the currently open sub-stream
      * @param elem          the buffered element waiting to be emitted to `sub`
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingSubDemandUpstreamGone(sub: SubSpoutStage, elem: AnyRef, mainRemaining: Long): State = state(
      request = (n, from) ⇒ {
        if (from eq sub) {
          sub.onNext(elem)
          sub.onComplete()
          stopComplete(out)
        } else if (from eq out) awaitingSubDemandUpstreamGone(sub, elem, mainRemaining ⊹ n)
        else stay()
      },

      cancel = {
        case x if x eq sub =>
          if (mainRemaining > 0) awaitingSubDemandUpstreamGone(emitNewSub(), elem, mainRemaining - 1)
          else noSubAwaitingMainDemandUpstreamGone(elem)
        case x if x eq out => awaitingSubDemandUpAndDownstreamGone(sub, elem)
        case _ => stay()
      })

    /**
      * One element buffered, sub-stream open, main downstream already cancelled.
      * Waiting for the next request from the sub-stream.
      *
      * @param sub  the currently open sub-stream
      * @param elem the buffered element waiting to be emitted to `sub`
      */
    def awaitingSubDemandDownstreamGone(sub: SubSpoutStage, elem: AnyRef): State = state(
      request = (n, from) ⇒ {
        if (from eq sub) {
          sub.onNext(elem)
          in.request(1)
          awaitingElemDownstreamGone(sub, subRemaining = n.toLong - 1)
        } else stay()
      },

      cancel = from => if (from eq sub) stopCancel(in) else stay(),
      onComplete = _ => awaitingSubDemandUpAndDownstreamGone(sub, elem),
      onError = stopErrorF(sub))

    /**
      * One element buffered, sub-stream open, upstream already completed, main downstream already cancelled.
      * Waiting for the next request from the sub-stream.
      *
      * @param sub the currently open sub-stream
      */
    def awaitingSubDemandUpAndDownstreamGone(sub: SubSpoutStage, elem: AnyRef): State = state(
      request = (_, from) ⇒ {
        if (from eq sub) {
          sub.onNext(elem)
          stopComplete(sub)
        } else stay()
      },

      cancel = from => if (from eq sub) stop() else stay())

    /**
      * No element buffered, sub-stream open, demand signalled to upstream.
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
          sub.xEvent(SubSpoutStage.EnableSubStreamStartTimeout)
          awaitingElemDownstreamGone(sub, subRemaining)
        case _ => stay()
      },

      onNext = (elem, _) ⇒ {
        if (subRemaining > 0) {
          sub.onNext(elem)
          in.request(1)
          awaitingElem(sub, subRemaining - 1, mainRemaining)
        } else awaitingSubDemand(sub, elem, mainRemaining)
      },

      onComplete = stopCompleteSubAndMainF(sub),
      onError = stopErrorSubAndMainF(sub))

    /**
      * No element buffered, sub-stream open, main downstream cancelled, demand signalled to upstream.
      * Waiting for the next element from upstream.
      *
      * @param sub           the currently open sub-stream
      * @param subRemaining  number of elements already requested by sub-stream but not yet delivered, >= 0
      */
    def awaitingElemDownstreamGone(sub: SubSpoutStage, subRemaining: Long): State = state(
      request = (n, from) ⇒ if (from eq sub) awaitingElemDownstreamGone(sub, subRemaining ⊹ n) else stay(),
      cancel = from => if (from eq sub) stopCancel(in) else stay(),

      onNext = (elem, _) ⇒ {
        if (subRemaining > 0) {
          sub.onNext(elem)
          in.request(1)
          awaitingElemDownstreamGone(sub, subRemaining - 1)
        } else awaitingSubDemandDownstreamGone(sub, elem)
      },

      onComplete = stopCompleteSubAndMainF(sub),
      onError = stopErrorSubAndMainF(sub))

    ///////////////////////// helpers //////////////////////////

    def emitNewSub() = {
      val s = new SubSpoutStage(this)
      out.onNext(new Spout(s))
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
