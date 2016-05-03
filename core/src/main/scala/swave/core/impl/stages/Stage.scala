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

package swave.core.impl.stages

import scala.annotation.{ switch, tailrec }
import swave.core.{ StreamEnv, PipeElem }
import swave.core.util._
import swave.core.impl._

private[swave] abstract class Stage extends Stage0 { this: PipeElem.Basic ⇒
  import Stage.InportStates

  type State = Stage.State

  // compile time constant
  private final val STOPPED = 0
  private final val CONNECTING = 2
  private final val CONNECTING_I = 3
  private final val RUNNING = 4
  private final val RUNNING_I = 5

  private[this] var _phase: Int = CONNECTING
  private[this] var _state: State = _
  private[this] var _mbs: Int = _
  private[this] var _inportState: InportStates = _
  private[this] var _buffer: ResizableRingBuffer[AnyRef] = _ // TODO: evaluate inlining vs. splitting into 3 more narrowly typed buffers

  protected final def initialState(s: State): Unit = _state = s

  protected final def configureFrom(env: StreamEnv): Unit =
    _mbs = env.settings.maxBatchSize

  final def start(ctx: StartContext): Unit =
    (_phase: @switch) match {
      case CONNECTING | CONNECTING_I ⇒
        _phase = RUNNING_I
        _state = _state.start(ctx)
        handleInterceptions()
      case _ ⇒ // trying to start again is ok
    }

  final def subscribe()(implicit from: Outport) =
    (_phase: @switch) match {
      case STOPPED ⇒ // ignore
      case CONNECTING | RUNNING ⇒
        _phase = _phase | 1
        _subscribe(from)
        handleInterceptions()
      case CONNECTING_I | RUNNING_I ⇒ storeInterception(StageStatics.LONGS(0), from)
    }

  private def _subscribe(from: Outport): Unit = _state = _state.subscribe(from)

  final def request(n: Long)(implicit from: Outport) =
    (_phase: @switch) match {
      case STOPPED ⇒ // ignore
      case CONNECTING | RUNNING ⇒
        _phase = _phase | 1
        _request(n, from)
        handleInterceptions()
      case CONNECTING_I | RUNNING_I ⇒
        storeInterception(StageStatics.LONGS(1), if (n < 16) StageStatics.LONGS(n.toInt) else new java.lang.Long(n), from)
    }

  private def _request(n: Long, from: Outport): Unit = {
    val count =
      if (n > _mbs) {
        from.asInstanceOf[Stage].updateInportState(this, n)
        _mbs
      } else n.toInt
    _state = _state.request(count, from)
  }

  final def cancel()(implicit from: Outport) =
    (_phase: @switch) match {
      case STOPPED ⇒ // ignore
      case CONNECTING | RUNNING ⇒
        _phase = _phase | 1
        from.asInstanceOf[Stage].clearInportState(this)
        _cancel(from)
        handleInterceptions()
      case CONNECTING_I | RUNNING_I ⇒
        from.asInstanceOf[Stage].clearInportState(this)
        storeInterception(StageStatics.LONGS(2), from)
    }

  private def _cancel(from: Outport): Unit = _state = _state.cancel(from)

  final def onSubscribe()(implicit from: Inport) =
    (_phase: @switch) match {
      case STOPPED ⇒ // ignore
      case CONNECTING | RUNNING ⇒
        _phase = _phase | 1
        _onSubscribe(from)
        handleInterceptions()
      case CONNECTING_I | RUNNING_I ⇒ storeInterception(StageStatics.LONGS(3), from)
    }

  private def _onSubscribe(from: Inport): Unit = _state = _state.onSubscribe(from)

  final def onNext(elem: AnyRef)(implicit from: Inport) =
    (_phase: @switch) match {
      case STOPPED ⇒ // ignore
      case CONNECTING | RUNNING ⇒
        _phase = _phase | 1
        _onNext(elem, from)
        handleInterceptions()
      case CONNECTING_I | RUNNING_I ⇒ storeInterception(StageStatics.LONGS(4), elem, from)
    }

  private def _onNext(elem: AnyRef, from: Inport): Unit = {
    @tailrec def rec(last: InportStates, current: InportStates): State =
      if (current ne null) {
        if (current.in eq from) {
          val next = _state.onNext(elem, from)
          if (current.tail ne current) { // is `current` still valid, i.e. not cancelled? (tail eq self is special condition)
            val newPending = current.pending - 1
            if (newPending == 0) {
              val newRemaining = current.remaining - _mbs
              if (newRemaining > 0) {
                current.pending = _mbs
                current.remaining = newRemaining
                from.request(_mbs.toLong)
              } else {
                if (last ne null) last.tail = current.tail else _inportState = current.tail
                from.request(current.remaining)
              }
            } else current.pending = newPending
          }
          next
        } else rec(current, current.tail)
      } else _state.onNext(elem, from)
    _state = rec(null, _inportState)
  }

  final def onComplete()(implicit from: Inport) =
    (_phase: @switch) match {
      case STOPPED ⇒ // ignore
      case CONNECTING | RUNNING ⇒
        _phase = _phase | 1
        _onComplete(from)
        handleInterceptions()
      case CONNECTING_I | RUNNING_I ⇒ storeInterception(StageStatics.LONGS(5), from)
    }

  private def _onComplete(from: Inport): Unit = {
    clearInportState(from)
    _state = _state.onComplete(from)
  }

  final def onError(error: Throwable)(implicit from: Inport) =
    (_phase: @switch) match {
      case STOPPED ⇒ // ignore
      case CONNECTING | RUNNING ⇒
        _phase = _phase | 1
        _onError(error, from)
        handleInterceptions()
      case CONNECTING_I | RUNNING_I ⇒ storeInterception(StageStatics.LONGS(6), error, from)
    }

  private def _onError(error: Throwable, from: Inport): Unit = {
    clearInportState(from)
    _state = _state.onError(error, from)
  }

  final def onExtraSignal(ev: AnyRef) =
    (_phase: @switch) match {
      case STOPPED ⇒ // ignore
      case CONNECTING | RUNNING ⇒
        _phase = _phase | 1
        _onExtraSignal(ev)
        handleInterceptions()
      case CONNECTING_I | RUNNING_I ⇒ storeInterception(StageStatics.LONGS(7), ev)
    }

  private def _onExtraSignal(ev: AnyRef): Unit = _state.onExtraSignal.applyOrElse(ev, swave.core.util.dropFunc)

  protected final implicit def self: this.type = this

  protected final def stay(): State = _state

  protected final def stop(): State = {
    _phase = STOPPED
    _buffer = null // don't hang on to elements
    stay()
  }

  protected final def stopF(out: Outport): State = stop()

  @tailrec protected final def cancelAll[L >: Null <: AbstractInportList[L]](ins: L, except: Inport = null): Unit =
    if (ins ne null) {
      if (ins.in ne except) ins.in.cancel()
      cancelAll(ins.tail)
    }

  protected final def cancelAllAndStopComplete[L >: Null <: AbstractInportList[L]](ins: L, except: Inport, out: Outport): State = {
    cancelAll(ins, except)
    stopComplete(out)
  }

  protected final def stopCancel(in: Inport): State = {
    in.cancel()
    stop()
  }

  protected final def stopCancel[L >: Null <: AbstractInportList[L]](ins: L, except: Inport = null): State = {
    cancelAll(ins, except)
    stop()
  }

  protected final def stopCancelF(in: Inport)(out: Outport): State = stopCancel(in)

  protected final def stopCancelF[L >: Null <: AbstractInportList[L]](ins: L)(out: Outport): State = stopCancel(ins)

  protected final def stopComplete(out: Outport): State = {
    out.onComplete()
    stop()
  }

  protected final def stopCompleteF(out: Outport)(in: Inport): State = stopComplete(out)

  protected final def cancelAllAndStopCompleteF[L >: Null <: AbstractInportList[L]](ins: L, out: Outport)(in: Inport): State = {
    cancelAll(ins, except = in)
    stopComplete(out)
  }

  protected final def stopError(e: Throwable, out: Outport): State = {
    out.onError(e)
    stop()
  }

  protected final def stopErrorF(out: Outport)(e: Throwable, in: Inport): State =
    stopError(e, out)

  protected final def cancelAllAndStopErrorF[L >: Null <: AbstractInportList[L]](ins: L, out: Outport)(e: Throwable, in: Inport): State = {
    cancelAll(ins, except = in)
    stopError(e, out)
  }

  private def storeInterception(signal: java.lang.Long, arg: AnyRef): Unit =
    if (!buffer.write(signal, arg))
      illegalState(s"Interception buffer overflow on signal '$signal($arg)' in $this")

  private def storeInterception(signal: java.lang.Long, arg0: AnyRef, arg1: AnyRef): Unit =
    if (!buffer.write(signal, arg0, arg1))
      illegalState(s"Interception buffer overflow on signal '$signal($arg0, $arg1)' in $this")

  private def buffer = {
    if (_buffer eq null) {
      val initialSize = roundUpToNextPowerOf2(_mbs)
      _buffer = new ResizableRingBuffer(initialSize, initialSize << 4)
    }
    _buffer
  }

  @tailrec private def handleInterceptions(): Unit =
    if ((_buffer ne null) && _buffer.nonEmpty) {
      def read() = _buffer.unsafeRead()
      read().asInstanceOf[java.lang.Long].intValue() match {
        case 0 ⇒ _subscribe(read().asInstanceOf[Stage])
        case 1 ⇒ _request(read().asInstanceOf[java.lang.Long].longValue(), read().asInstanceOf[Stage])
        case 2 ⇒ _cancel(read().asInstanceOf[Stage])
        case 3 ⇒ _onSubscribe(read().asInstanceOf[Stage])
        case 4 ⇒ _onNext(read(), read().asInstanceOf[Stage])
        case 5 ⇒ _onComplete(read().asInstanceOf[Stage])
        case 6 ⇒ _onError(read().asInstanceOf[Throwable], read().asInstanceOf[Stage])
        case 7 ⇒ _onExtraSignal(read())
      }
      handleInterceptions()
    } else _phase = _phase & ~1

  private def updateInportState(in: Inport, requested: Long): Unit = {
    require(requested > _mbs)
    @tailrec def rec(current: InportStates): Unit =
      if (current ne null) {
        if (current.in eq in) current.remaining = current.remaining ⊹ requested
        else rec(current.tail)
      } else _inportState = new InportStates(in, _inportState, _mbs, requested - _mbs)
    rec(_inportState)
  }

  private def clearInportState(in: Inport): Unit =
    _inportState = _inportState.remove(in)

  protected final def fullState(
    name: String,
    start: StartContext ⇒ State = unexpectedStart,
    subscribe: Outport ⇒ State = unexpectedSubscribe,
    request: (Int, Outport) ⇒ State = unexpectedRequestInt,
    cancel: Outport ⇒ State = unexpectedCancel,
    onSubscribe: Inport ⇒ State = unexpectedOnSubscribe,
    onNext: (AnyRef, Inport) ⇒ State = unexpectedOnNext,
    onComplete: Inport ⇒ State = unexpectedOnComplete,
    onError: (Throwable, Inport) ⇒ State = unexpectedOnError,
    onExtraSignal: Stage.ExtraSignalHandler = unexpectedExtra) =
    new State(name, start, subscribe, request, cancel, onSubscribe, onNext, onComplete, onError, onExtraSignal)

  protected final def stateName: String = _state.name

  override def toString = s"${getClass.getSimpleName}@${identityHash(this)}"

  protected[stages] final def assertNotRunning(): Unit =
    if ((_phase & ~1) == RUNNING)
      throw new IllegalStateException(s"Stage $this was running when not allowed to")
}

private[swave] object Stage {

  type ExtraSignalHandler = PartialFunction[AnyRef, Unit]

  private[stages] final class State private[Stage] (
    val name: String,
    val start: StartContext ⇒ State,
    val subscribe: Outport ⇒ State,
    val request: (Int, Outport) ⇒ State,
    val cancel: Outport ⇒ State,
    val onSubscribe: Inport ⇒ State,
    val onNext: (AnyRef, Inport) ⇒ State,
    val onComplete: Inport ⇒ State,
    val onError: (Throwable, Inport) ⇒ State,
    val onExtraSignal: ExtraSignalHandler)

  private class InportStates(in: Inport, tail: InportStates,
    var pending: Int, // already requested from `in` but not yet received
    var remaining: Long) // already requested by us but not yet forwarded to `in`
      extends AbstractInportList[InportStates](in, tail)

  private[stages] class OutportStates(out: Outport, tail: OutportStates,
    var remaining: Long) // requested by this `out` but not yet delivered, i.e. unfulfilled demand
      extends AbstractOutportList[OutportStates](out, tail)

  // extra signals
  case object PostRun
  case object Cleanup
}