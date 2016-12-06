/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import java.util.function.BiConsumer

import scala.util.control.NonFatal
import swave.core.impl.stages.{InOutStage, StageImpl}
import swave.core.impl.stages.spout.SubSpoutStage
import swave.core.impl.{Inport, Outport, RunSupport}
import swave.core.{Spout, Stage}
import swave.core.macros._
import swave.core.util._
import GroupByStage.Sub

// format: OFF
@StageImplementation(fullInterceptions = true)
private[core] final class GroupByStage(maxSubstreams: Int, reopenCancelledSubs: Boolean, eagerComplete: Boolean,
                                       keyFun: Any ⇒ AnyRef)
  extends InOutStage with RunSupport.RunContextAccess { stage =>

  require(maxSubstreams > 0, "`maxSubStreams` must be > 0")

  def kind = Stage.Kind.InOut.GroupBy(maxSubstreams, reopenCancelledSubs, eagerComplete, keyFun)

  private[this] val subMap = new java.util.HashMap[Any, Sub]()

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForXStart(this)
    ctx.registerForRunContextAccess(this)
    running(in, out)
  }

  def running(in: Inport, out: Outport) = {

    def awaitingXStart() = state(
      xStart = () => {
        in.request(1)
        awaitingElem(0)
      })

    /**
      * Zero or more sub-streams open.
      * Waiting for pending element from upstream.
      *
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingElem(mainRemaining: Long): State = {
      requireState(mainRemaining >= 0)
      state(
        request = (n, from) => {
          from match {
            case sub: Sub if sub.isCancelled => stay()
            case sub: Sub =>
              sub.remaining += n
              awaitingElem(mainRemaining)
            case x if x eq out => awaitingElem(mainRemaining ⊹ n)
          }
        },

        cancel = {
          case sub: Sub if sub.isCancelled => stay()
          case sub: Sub =>
            if (reopenCancelledSubs) subMap.remove(sub.key)
            sub.markCancelled()
            stay()
          case x if x eq out =>
            if (!subMap.isEmpty) {
              if (eagerComplete) {
                completeAllSubs()
                stopCancel(in)
              } else awaitingElemMainGone()
            } else stopCancel(in)
        },

        onNext = (elem, _) => {
          var funError: Throwable = null
          val key = try keyFor(elem) catch { case NonFatal(e) => { funError = e; null } }
          if (funError eq null) {
            subMap.get(key) match {
              case null if subMap.size() < maxSubstreams => // new key
                if (mainRemaining > 0) {
                  val sub = createRegisterAndEmitNewSub(key)
                  awaitingSubDemand(sub, elem, mainRemaining - 1)
                } else awaitingMainDemand(key, elem)
              case null =>
                val msg = s"Cannot open substream for key '$key': max substream count of $maxSubstreams reached"
                cancelInAndStopErrorSubsAndMain(new IllegalStateException(msg))
              case sub if sub.isCancelled =>
                in.request(1)
                stay() // drop elem
              case sub =>
                if (sub.remaining > 0) {
                  sub.onNext(elem)
                  sub.remaining -= 1
                  in.request(1)
                  stay()
                } else awaitingSubDemand(awaitingFrom = sub, elem, mainRemaining)
            }
          } else cancelInAndStopErrorSubsAndMain(funError)
        },

        onComplete = stopCompleteSubsAndMainF,
        onError = stopErrorSubsAndMainF)
    }

    /**
      * At least one sub-stream open. One element buffered. No element pending from upstream.
      * Awaiting demand from sub for buffered elem.
      *
      * @param awaitingFrom sub we are awaiting demand from
      * @param currentElem the buffered element to be delivered to `awaitingFrom` sub
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingSubDemand(awaitingFrom: Sub, currentElem: AnyRef, mainRemaining: Long): State = {
      requireState(!subMap.isEmpty && mainRemaining >= 0)
      state(
        request = (n, from) => {
          from match {
            case sub: Sub if sub.isCancelled => stay()
            case sub: Sub if sub eq awaitingFrom =>
              sub.onNext(currentElem)
              sub.remaining = (n - 1).toLong
              in.request(1)
              awaitingElem(mainRemaining)
            case sub: Sub =>
              sub.remaining += n
              stay()
            case x if x eq out => awaitingSubDemand(awaitingFrom, currentElem, mainRemaining ⊹ n)
          }
        },

        cancel = {
          case sub: Sub if sub.isCancelled => stay()
          case sub: Sub =>
            val key = sub.key
            if (reopenCancelledSubs) subMap.remove(key)
            sub.markCancelled()
            if (sub eq awaitingFrom) {
              if (reopenCancelledSubs) {
                if (mainRemaining > 0) {
                  val newSub = createRegisterAndEmitNewSub(key)
                  awaitingSubDemand(newSub, currentElem, mainRemaining - 1)
                } else awaitingMainDemand(key, currentElem)
              } else {
                in.request(1)
                awaitingElem(mainRemaining) // drop currentElem
              }
            } else stay()
          case x if x eq out =>
            if (eagerComplete) {
              in.cancel()
              completeAllSubs(except = awaitingFrom)
              awaitingSubDemandAllOthersGone(awaitingFrom, currentElem)
            } else awaitingSubDemandMainGone(awaitingFrom, currentElem)
        },

        onComplete = _ => {
          completeAllSubs(except = awaitingFrom)
          awaitingSubDemandUpstreamGone(awaitingFrom, currentElem, mainRemaining)
        },

        onError = stopErrorSubsAndMainF)
    }

    /**
      * Zero or more sub-streams open. One element buffered. No element pending from upstream.
      * Awaiting demand from main downstream.
      *
      * @param key the key for the `currentElement`
      * @param currentElem the buffered element
      */
    def awaitingMainDemand(key: AnyRef, currentElem: AnyRef): State = state(
      request = (n, from) => {
        from match {
          case sub: Sub if sub.isCancelled => stay()
          case sub: Sub =>
            sub.remaining += n
            stay()
          case x if x eq out =>
            val sub = createRegisterAndEmitNewSub(key)
            awaitingSubDemand(sub, currentElem, (n - 1).toLong)
        }
      },

      cancel = {
        case sub: Sub if sub.isCancelled => stay()
        case sub: Sub =>
          if (reopenCancelledSubs) subMap.remove(sub.key)
          sub.markCancelled()
          stay()
        case x if x eq out =>
          if (!subMap.isEmpty) {
            if (eagerComplete) {
              completeAllSubs()
              stopCancel(in)
            } else {
              in.request(1)
              awaitingElemMainGone() // drop currentElem
            }
          } else stopCancel(in)
      },

      onComplete = _ => {
        completeAllSubs()
        awaitingMainDemandUpstreamGone(key, currentElem)
      },

      onError = stopErrorSubsAndMainF)

    /**
      * At least one sub-streams open. Main downstream cancelled.
      * Waiting for pending element from upstream.
      */
    def awaitingElemMainGone(): State = {
      requireState(!eagerComplete && !subMap.isEmpty)
      state(
        request = (n, from) => {
          from match {
            case sub: Sub if sub.isCancelled => stay()
            case sub: Sub =>
              sub.remaining += n
              stay()
          }
        },

        cancel = {
          case sub: Sub if sub.isCancelled => stay()
          case sub: Sub =>
            if (reopenCancelledSubs) subMap.remove(sub.key)
            sub.markCancelled()
            if (subMap.isEmpty) stopCancel(in) else stay()
        },

        onNext = (elem, _) => {
          var funError: Throwable = null
          val key = try keyFor(elem) catch { case NonFatal(e) => { funError = e; null } }
          if (funError eq null) {
            subMap.get(key) match {
              case null =>
                in.request(1)
                stay() // drop elem
              case sub if sub.isCancelled =>
                in.request(1)
                stay() // drop elem
              case sub =>
                if (sub.remaining > 0) {
                  sub.onNext(elem)
                  sub.remaining -= 1
                  in.request(1)
                  stay()
                } else awaitingSubDemandMainGone(awaitingFrom = sub, elem)
            }
          } else cancelInAndStopErrorSubs(funError)
        },

        onComplete = stopCompleteSubsF,
        onError = stopErrorSubsF)
    }

    /**
      * At least one sub-stream open. One element buffered. No element pending from upstream.
      * Main downstream cancelled. Awaiting demand from sub for buffered elem.
      *
      * @param awaitingFrom sub we are awaiting demand from
      * @param currentElem the buffered element to be delivered to `awaitingFrom` sub
      */
    def awaitingSubDemandMainGone(awaitingFrom: Sub, currentElem: AnyRef): State = {
      requireState(!eagerComplete && !subMap.isEmpty)
      state(
        request = (n, from) => {
          from match {
            case sub: Sub if sub.isCancelled => stay()
            case sub: Sub if sub eq awaitingFrom =>
              sub.onNext(currentElem)
              sub.remaining = (n - 1).toLong
              in.request(1)
              awaitingElemMainGone()
            case sub: Sub =>
              sub.remaining += n
              stay()
          }
        },

        cancel = {
          case sub: Sub if sub.isCancelled => stay()
          case sub: Sub =>
            sub.markCancelled()
            if (sub eq awaitingFrom) {
              in.request(1)
              awaitingElemMainGone() // drop currentElem
            } else stay()
        },

        onComplete = _ => {
          completeAllSubs(except = awaitingFrom)
          awaitingSubDemandAllOthersGone(awaitingFrom, currentElem)
        },

        onError = stopErrorSubsAndMainF)
    }

    /**
      * Exactly one sub-stream open. One element buffered. Upstream completed.
      * Awaiting demand from sub for buffered elem.
      *
      * @param awaitingFrom sub we are awaiting demand from
      * @param currentElem the buffered element to be delivered to `awaitingFrom` sub
      * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
      */
    def awaitingSubDemandUpstreamGone(awaitingFrom: Sub, currentElem: AnyRef, mainRemaining: Long): State = {
      requireState(!subMap.isEmpty && mainRemaining >= 0)
      state(
        request = (n, from) => {
          from match {
            case sub: Sub if sub.isCancelled => stay()
            case sub: Sub if sub eq awaitingFrom =>
              sub.onNext(currentElem)
              sub.onComplete()
              stopComplete(out)
            case x if x eq out => awaitingSubDemandUpstreamGone(awaitingFrom, currentElem, mainRemaining ⊹ n)
          }
        },

        cancel = {
          case sub: Sub if sub.isCancelled => stay()
          case sub: Sub if sub eq awaitingFrom =>
            if (reopenCancelledSubs) {
              val key = sub.key
              subMap.remove(key)
              sub.markCancelled()
              if (mainRemaining > 0) {
                val newSub = createRegisterAndEmitNewSub(key)
                awaitingSubDemandUpstreamGone(newSub, currentElem, mainRemaining - 1)
              } else awaitingMainDemandUpstreamGone(key, currentElem)
            } else stopComplete(out)
          case x if x eq out => awaitingSubDemandAllOthersGone(awaitingFrom, currentElem)
        })
    }

    /**
      * No sub-streams open. One element buffered. Upstream completed.
      * Awaiting demand from main downstream.
      *
      * @param key the key for the `currentElement`
      * @param currentElem the buffered element
      */
    def awaitingMainDemandUpstreamGone(key: AnyRef, currentElem: AnyRef): State = state(
      request = (n, from) => {
        from match {
          case sub: Sub if sub.isCancelled => stay()
          case x if x eq out =>
            val sub = createRegisterAndEmitNewSub(key)
            awaitingSubDemandUpstreamGone(sub, currentElem, (n - 1).toLong)
        }
      },

      cancel = {
        case sub: Sub if sub.isCancelled => stay()
        case x if x eq out => stop() // drop currentElem
      },

      onComplete = _ => stay(),
      onError = (_, _) => stay())

    /**
      * Exactly one sub-stream open. One element buffered. Upstream completed. Downstream cancelled.
      * Awaiting demand from sub for buffered elem.
      *
      * @param awaitingFrom sub we are awaiting demand from
      * @param currentElem the buffered element to be delivered to `awaitingFrom` sub
      */
    def awaitingSubDemandAllOthersGone(awaitingFrom: Sub, currentElem: AnyRef): State = state(
      request = (_, from) => {
        from match {
          case sub: Sub if sub.isCancelled => stay()
          case sub: Sub if sub eq awaitingFrom =>
            sub.onNext(currentElem)
            stopComplete(sub)
        }
      },

      cancel = {
        case sub: Sub if sub.isCancelled => stay()
        case sub: Sub if sub eq awaitingFrom => stop() // drop currentElem
      },

      onComplete = _ => stay(),
      onError = (_, _) => stay())

    ///////////////////////// helpers //////////////////////////

    def keyFor(elem: AnyRef): AnyRef =
      keyFun(elem) match {
        case null => sys.error(s"groupBy key function returned `null` for elem `$elem` which is not allowed")
        case x => x
      }

    def createRegisterAndEmitNewSub(key: AnyRef): Sub = {
      val sub = createAndRegisterNewSub(key)
      out.onNext(new Spout(sub).asInstanceOf[AnyRef])
      sub
    }

    def createAndRegisterNewSub(key: AnyRef): Sub = {
      val sub = new Sub(key, runContext, this)
      subMap.put(key, sub)
      sub
    }

    def completeAllSubs(except: Sub = null): Unit =
      subMap.forEach {
        new BiConsumer[Any, Sub] {
          override def accept(key: Any, sub: Sub): Unit =
            if (sub ne except) {
              sub.onComplete()
              sub.markCancelled()
            }
        }
      }

    def errorAll(e: Throwable): Unit =
      subMap.forEach {
        new BiConsumer[Any, Sub] {
          override def accept(key: Any, sub: Sub): Unit = {
            sub.onError(e)
            sub.markCancelled()
          }
        }
      }

    def stopCompleteSubsAndMainF(i: Inport): State = {
      completeAllSubs()
      stopComplete(out)
    }

    def stopCompleteSubsF(i: Inport): State = {
      completeAllSubs()
      stop()
    }

    def stopErrorSubsAndMainF(e: Throwable, i: Inport): State = {
      errorAll(e)
      stopError(e, out)
    }

    def stopErrorSubsF(e: Throwable, i: Inport): State = {
      errorAll(e)
      stop(e)
    }

    def cancelInAndStopErrorSubsAndMain(e: Throwable): State = {
      out.onError(e)
      cancelInAndStopErrorSubs(e)
    }

    def cancelInAndStopErrorSubs(e: Throwable): State = {
      errorAll(e)
      stopCancel(in)
    }

    awaitingXStart()
  }
}

private object GroupByStage {

  private final class Sub(var key: AnyRef, _ctx: RunSupport.RunContext, _in: StageImpl)
    extends SubSpoutStage(_ctx, _in) {

    var remaining = 0L // the number of elements already requested by this sub but not yet delivered to it

    def isCancelled = key eq null
    def markCancelled(): Unit = key = null
  }
}