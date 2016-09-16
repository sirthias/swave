/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.spout

import org.jctools.queues.MpscChunkedArrayQueue
import scala.annotation.tailrec
import swave.core.PipeElem
import swave.core.impl.Outport
import swave.core.impl.stages.StreamTermination
import swave.core.macros.StageImpl
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class PushSpoutStage(queue: MpscChunkedArrayQueue[AnyRef], notifyOnDequeued: Int => Unit,
                                         notifyOnCancel: () => Unit) extends SpoutStage with PipeElem.Spout.Push {
  import PushSpoutStage._

  def pipeElemType: String = "Spout.push"
  def pipeElemParams: List[Any] = Nil

  initialState(awaitingSubscribe(StreamTermination.None))

  def awaitingSubscribe(term: StreamTermination): State = state(
    intercept = false,

    subscribe = from ⇒ {
      _outputPipeElem = from.pipeElem
      from.onSubscribe()
      ready(from, term)
    },

    xEvent = {
      case Signal.NewAvailable => stay()
      case Signal.Complete => awaitingSubscribe(term transitionTo StreamTermination.Completed)
      case Signal.ErrorComplete(e) => awaitingSubscribe(term transitionTo StreamTermination.Error(e))
    })

  def ready(out: Outport, term: StreamTermination): State = state(
    intercept = false,

    xSeal = c ⇒ {
      configureFrom(c)
      out.xSeal(c)
      if (term != StreamTermination.None) {
        c.registerForXStart(this)
        awaitingXStart(out, term)
      } else running(out)
    },

    xEvent = {
      case Signal.NewAvailable => stay()
      case Signal.Complete => ready(out, term transitionTo StreamTermination.Completed)
      case Signal.ErrorComplete(e) => ready(out, term transitionTo StreamTermination.Error(e))
    })

  def awaitingXStart(out: Outport, term: StreamTermination): State = state(
    xStart = () => {
      term match {
        case StreamTermination.Error(e) => stopError(e, out)
        case _ => if (queue.isEmpty) stopComplete(out) else draining()
      }
    },

    xEvent = { case _: Signal => stay() })

  def running(out: Outport): State = {

    /**
     * Downstream active. No completion received yet.
     *
     * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
     */
    def active(remaining: Long): State = state(
      request = (n, _) ⇒ active(dispatch(remaining ⊹ n)),

      cancel = _ => {
        notifyOnCancel()
        stop()
      },

      xEvent = {
        case Signal.NewAvailable => active(dispatch(remaining))
        case Signal.Complete => if (queue.isEmpty) stopComplete(out) else draining()
        case Signal.ErrorComplete(e) => stopError(e, out)
      })

    /**
     * Downstream active. Manual completion received, queue non-empty.
     */
    def draining(): State = state(
      request = (n, _) ⇒ if (dispatch(n.toLong) > 0 || queue.isEmpty) stopComplete(out) else stay(),

      cancel = _ => {
        notifyOnCancel()
        stop()
      },

      xEvent = { case _: Signal => stay() })

    // returns the new `remaining` value
    @tailrec def dispatch(rem: Long, count: Int = 0): Long = {
      val elem = if (rem > 0) queue.poll() else null
      if (elem ne null) {
        out.onNext(elem)
        dispatch(rem - 1, count + 1)
      } else {
        if (count > 0) notifyOnDequeued(count)
        rem
      }
    }

    active(0)
  }
}

private[core] object PushSpoutStage {

  sealed trait Signal
  object Signal {
    case object NewAvailable extends Signal
    case object Complete extends Signal
    final case class ErrorComplete(e: Throwable) extends Signal
  }
}