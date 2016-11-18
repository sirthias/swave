/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.fanout

import scala.annotation.tailrec
import swave.core.Stage
import swave.core.impl.stages.FanOutStage
import swave.core.impl.util.MultiReaderRingBuffer
import swave.core.impl.{Inport, Outport}
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImplementation(fullInterceptions = true)
private[core] final class FanOutBroadcastBufferedStage(bufferSize: Int, requestThreshold: Int, eagerCancel: Boolean)
  extends FanOutStage {

  requireArg(bufferSize > 0, "`bufferSize` must be > 0")

  def kind = Stage.Kind.FanOut.BroadcastBuffered(bufferSize, requestThreshold, eagerCancel)

  type OutportCtx = FanOutBroadcastBufferedStage.OutportContextWithCursor

  private[this] val buffer = new MultiReaderRingBuffer[AnyRef](roundUpToPowerOf2(bufferSize))

  protected def createOutportCtx(out: Outport, tail: OutportCtx): OutportCtx =
    new FanOutBroadcastBufferedStage.OutportContextWithCursor(out, tail)

  connectFanOutAndSealWith { (ctx, in, outs) ⇒
    ctx.registerForXStart(this)
    awaitingXStart(in, outs)
  }

  /**
    * @param in  the active upstream
    * @param outs the active downstreams
    */
  def awaitingXStart(in: Inport, outs: OutportCtx) = state(
    xStart = () => {
      in.request(bufferSize.toLong)
      running(in, outs, bufferSize.toLong)
    })

  /**
    * @param in        the active upstream
    * @param outs      the active downstreams
    * @param pending   number of elements already requested from upstream but not yet received, >= 0
    */
  def running(in: Inport, outs: OutportCtx, pending: Long): State = {

    @tailrec def handleDemand(n: Int, out: Outport, os: OutportCtx, current: OutportCtx,
                              pend: Long, minRemaining: Long = Long.MaxValue): State =
      if (current ne null) {
        val rem = if (current.out eq out) current.remaining ⊹ n else current.remaining
        current.remaining = emitNext(current, rem, os)
        handleDemand(n, out, os, current.tail, pend, math.min(current.remaining, minRemaining))
      } else {
        val alreadyRequested = pend ⊹ buffer.count
        val target = minRemaining ⊹ bufferSize
        val delta = target - alreadyRequested
        val newPending =
          if (delta > requestThreshold) {
            in.request(delta)
            pend + delta
          } else pend
        running(in, os, newPending)
      }

    state(
      request = (n, out) ⇒ handleDemand(n, out, outs, outs, pending),

      cancel = out ⇒ {
        @tailrec def rec(prev: OutportCtx, current: OutportCtx): State =
          if (current ne null) {
            if (current.out eq out) {
              val newOuts = if (prev ne null) { prev.tail = current.tail; outs } else current.tail
              if (eagerCancel || newOuts.isEmpty) {
                in.cancel()
                completeAll(newOuts)
              } else {
                buffer.releaseCursor(current, newOuts)
                handleDemand(0, null, newOuts, newOuts, pending)
              }
            } else rec(current, current.tail)
          } else stay()
        rec(null, outs)
      },

      onNext = (elem, _) ⇒ {
        requireState(buffer.canWrite)
        buffer.unsafeWrite(elem)
        handleDemand(0, null, outs, outs, pending - 1)
      },

      onComplete = _ ⇒ {
        if (buffer.isEmpty) completeAll(outs)
        else draining(completeFullyDrained(outs))
      },

      onError = (e, _) ⇒ {
        @tailrec def rec(current: OutportCtx): State =
          if (current ne null) {
            current.out.onError(e)
            rec(current.tail)
          } else stop(e)
        rec(outs)
      })
  }

  /**
    * Upstream completed, at least one downstream active and buffer non-empty.
    *
    * @param outs the active downstreams
    */
  def draining(outs: OutportCtx): State = {
    requireState(outs.nonEmpty && buffer.nonEmpty)
    state(
      request = (n, out) ⇒ {
        @tailrec def rec(prev: OutportCtx, current: OutportCtx): State =
          if (current ne null) {
            if (current.out eq out) {
              val rem = emitNext(current, n.toLong, outs)
              if (!buffer.canRead(current)) {
                out.onComplete()
                val newOuts = if (prev ne null) { prev.tail = current.tail; outs } else current.tail
                if (newOuts.isEmpty) stop()
                else draining(newOuts)
              } else { requireState(rem == 0); stay() }
            } else rec(current, current.tail)
          } else stay()
        rec(null, outs)
      },

      cancel = out ⇒ {
        @tailrec def rec(prev: OutportCtx, current: OutportCtx): State =
          if (current ne null) {
            if (current.out eq out) {
              val newOuts = if (prev ne null) { prev.tail = current.tail; outs } else current.tail
              if (!eagerCancel) {
                if (newOuts.nonEmpty) {
                  buffer.releaseCursor(current, newOuts)
                  draining(newOuts)
                } else stop()
              } else completeAll(newOuts)
            } else rec(current, current.tail)
          } else stay()
        rec(null, outs)
      })
  }

  /**
    * Emit up to `remaining` elements from the buffer to the given downstream.
    * Returns the new `remaining` value.
    */
  @tailrec
  private def emitNext(o: OutportCtx, remaining: Long, outs: OutportCtx): Long =
    if (remaining > 0 && buffer.canRead(o)) {
      o.out.onNext(buffer.unsafeRead(o, outs))
      emitNext(o, remaining - 1, outs)
    } else remaining

  /**
    * Walks the list of outs and completes every out whose cursor has reached the end of the buffer.
    * Returns the updated list or null, if no out remains.
    */
  private def completeFullyDrained(outs: OutportCtx): OutportCtx = {
    @tailrec def rec(prev: OutportCtx, current: OutportCtx, result: OutportCtx): OutportCtx =
      if (current ne null) {
        if (!buffer.canRead(current)) {
          current.out.onComplete()
          if (prev ne null) {
            prev.tail = current.tail
            rec(prev, current.tail, result)
          } else rec(null, current.tail, current.tail)
        } else {
          requireState(current.remaining == 0)
          rec(current, current.tail, result)
        }
      } else result
    rec(null, outs, outs)
  }

  @tailrec
  private def completeAll(current: OutportCtx): State =
    if (current ne null) {
      current.out.onComplete()
      completeAll(current.tail)
    } else stop()
}

private[fanout] object FanOutBroadcastBufferedStage {

  private[fanout] final class OutportContextWithCursor(out: Outport, tail: OutportContextWithCursor)
    extends FanOutStage.OutportContext[OutportContextWithCursor](out, tail) with MultiReaderRingBuffer.Cursor {
    var cursor: Int = _
  }
}
