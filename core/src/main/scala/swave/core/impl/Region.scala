/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.FiniteDuration
import scala.annotation.{switch, tailrec}
import swave.core.internal.agrona.ThreadHints
import swave.core.impl.stages.StageImpl
import swave.core.impl.util.{ImsiList, ResizableRingBuffer}
import swave.core.macros._
import swave.core.util._
import swave.core._

private[swave] final class Region private[impl] (val entryPoint: StageImpl, val runContext: RunContext)
    extends Stage.Region { self =>
  import Region._

  val mbs: Int = runContext.env.settings.maxBatchSize

  /*
    null: virgin
    SignalQueue: if in prestart
    StreamRunner: if async
    Region.Sync: if sync
    Region.Busy: if another thread has acquired the "lock"
   */
  private[this] val ref = new AtomicReference[AnyRef]

  /////////////////////////// potentially called from other threads /////////////////////////
  @tailrec def enqueueRequest(target: StageImpl, n: Long, from: Outport): Unit =
    ref.get match {
      case null           => { ref.compareAndSet(null, new SignalQueue(mbs)); enqueueRequest(target, n, from) }
      case x: SignalQueue => x.enqueueRequest(target, n, from)
      case Busy           => { ThreadHints.onSpinWait(); enqueueRequest(target, n, from) }
      case x              => dispatchRequest(x, target, n, from)
    }
  private[Region] def dispatchRequest(channel: AnyRef, target: StageImpl, n: Long, from: Outport): Unit =
    channel match {
      case Sync            => target.request(n)(from)
      case x: StreamRunner => x.enqueue(new StreamRunner.Message.Request(target, n, from))
    }

  def enqueueCancel(target: StageImpl, from: Outport): Unit =
    ref.get match {
      case null           => { ref.compareAndSet(null, new SignalQueue(mbs)); enqueueCancel(target, from) }
      case x: SignalQueue => x.enqueueCancel(target, from)
      case Busy           => { ThreadHints.onSpinWait(); enqueueCancel(target, from) }
      case x              => dispatchCancel(x, target, from)
    }
  private[Region] def dispatchCancel(channel: AnyRef, target: StageImpl, from: Outport): Unit =
    channel match {
      case Sync            => target.cancel()(from)
      case x: StreamRunner => x.enqueue(new StreamRunner.Message.Cancel(target, from))
    }

  def enqueueOnNext(target: StageImpl, elem: AnyRef, from: Inport): Unit =
    ref.get match {
      case x: StreamRunner => x.enqueue(new StreamRunner.Message.OnNext(target, elem, from))
      case Busy            => { ThreadHints.onSpinWait(); enqueueOnNext(target, elem, from) }
      case x               => throw new IllegalStateException(s"Unexpected ref state `$x`")
    }

  def enqueueOnComplete(target: StageImpl, from: Inport): Unit =
    ref.get match {
      case null           => { ref.compareAndSet(null, new SignalQueue(mbs)); enqueueOnComplete(target, from) }
      case x: SignalQueue => x.enqueueOnComplete(target, from)
      case Busy           => { ThreadHints.onSpinWait(); enqueueOnComplete(target, from) }
      case x              => dispatchOnComplete(x, target, from)
    }
  private[Region] def dispatchOnComplete(channel: AnyRef, target: StageImpl, from: Inport): Unit =
    channel match {
      case Sync            => target.onComplete()(from)
      case x: StreamRunner => x.enqueue(new StreamRunner.Message.OnComplete(target, from))
    }

  def enqueueOnError(target: StageImpl, e: Throwable, from: Inport): Unit =
    ref.get match {
      case null           => { ref.compareAndSet(null, new SignalQueue(mbs)); enqueueOnError(target, e, from) }
      case x: SignalQueue => x.enqueueOnError(target, e, from)
      case Busy           => { ThreadHints.onSpinWait(); enqueueOnError(target, e, from) }
      case x              => dispatchOnError(x, target, e, from)
    }
  private[Region] def dispatchOnError(channel: AnyRef, target: StageImpl, e: Throwable, from: Inport): Unit =
    channel match {
      case Sync            => target.onError(e)(from)
      case x: StreamRunner => x.enqueue(new StreamRunner.Message.OnError(target, e, from))
    }

  def enqueueXEvent(target: StageImpl, ev: AnyRef): Unit =
    ref.get match {
      case null           => { ref.compareAndSet(null, new SignalQueue(mbs)); enqueueXEvent(target, ev) }
      case x: SignalQueue => x.enqueueXEvent(target, ev)
      case Busy           => { ThreadHints.onSpinWait(); enqueueXEvent(target, ev) }
      case x              => dispatchXEvent(x, target, ev)
    }
  private[Region] def dispatchXEvent(channel: AnyRef, target: StageImpl, ev: AnyRef): Unit =
    channel match {
      case Sync            => target.xEvent(ev)
      case x: StreamRunner => x.enqueue(new StreamRunner.Message.XEvent(target, ev, x))
    }

  def env: StreamEnv            = runContext.env
  def state: Stage.Region.State = syncedImpl.state
  def parent: Option[Region]    = Option(impl.parent)
  def stagesTotalCount: Int     = syncedImpl.stagesTotalCount
  def stagesActiveCount: Int    = syncedImpl.stagesActiveCount

  private def syncedImpl = { ref.get(); impl } // make sure we read the most current `impl`

  override def toString: String = s"Region($state)@${Integer.toHexString(System.identityHashCode(this))}"

  /////////////////////////// called from the region's thread /////////////////////////

  private var _impl: Impl = new PreStart
  def impl: Impl          = _impl

  private[swave] sealed abstract class Impl {
    def state: Stage.Region.State
    def parent: Region
    def stagesTotalCount: Int
    def stagesActiveCount: Int

    private[impl] def registerStageStopped(): Unit

    // PreStart only
    def registerForXStart(stage: StageImpl): Unit                                       = `n/a`
    private[impl] def becomeSubRegionOf(parent: Region): Unit                           = `n/a`
    private[impl] def registerStageSealed(): Unit                                       = `n/a`
    private[impl] def requestBridging(in: Inport, stage: StageImpl, out: Outport): Unit = `n/a`
    private[impl] def processBridgingRequests(): Unit                                   = `n/a`
    private[impl] def start(async: Boolean): Unit                                       = `n/a`

    // PreStart + AsyncRunning
    private[Region] def assignedDispatcherId: String                               = `n/a` // none (null), default (empty) or named (non-empty)
    private[impl] def requestDispatcherAssignment(dispatcherId: String = ""): Unit = `n/a`

    // SyncRunning + AsyncRunning
    private[impl] def scheduleSubStreamStartTimeout(stage: StageImpl): Cancellable                    = `n/a`
    private[impl] def enqueueSubscribeInterception(target: StageImpl, from: Outport): Unit            = `n/a`
    private[impl] def enqueueRequestInterception(target: StageImpl, n: Int, from: Outport): Unit      = `n/a`
    private[impl] def enqueueCancelInterception(target: StageImpl, from: Outport): Unit               = `n/a`
    private[impl] def enqueueOnSubscribeInterception(target: StageImpl, from: Inport): Unit           = `n/a`
    private[impl] def enqueueOnNextInterception(target: StageImpl, elem: AnyRef, from: Inport): Unit  = `n/a`
    private[impl] def enqueueOnCompleteInterception(target: StageImpl, from: Inport): Unit            = `n/a`
    private[impl] def enqueueOnErrorInterception(target: StageImpl, e: Throwable, from: Inport): Unit = `n/a`
    private[impl] def enqueueXEventInterception(target: StageImpl, ev: AnyRef): Unit                  = `n/a`

    // AsyncRunning only
    private[Region] def runner: StreamRunner                                                 = `n/a`
    private[impl] def scheduleTimeout(target: StageImpl, delay: FiniteDuration): Cancellable = `n/a`

    protected final def `n/a` = throw new IllegalStateException(toString)
  }

  private[swave] final class PreStart extends Impl {
    private[this] var _dispatcherId: String                = _
    private[this] var _needXStart: List[StageImpl]         = Nil
    private[this] var _bridgingRequests: BridgeRequestList = _
    def state                                              = Stage.Region.State.PreStart
    var parent: Region                                     = _
    var stagesTotalCount                                   = 0
    def stagesActiveCount                                  = 0

    override def registerStageStopped(): Unit              = stagesTotalCount -= 1
    override def registerForXStart(stage: StageImpl): Unit = _needXStart ::= stage
    override def registerStageSealed(): Unit               = stagesTotalCount += 1
    override def requestBridging(in: Inport, stage: StageImpl, out: Outport): Unit =
      if ((in ne stage) && (stage ne out) && (in ne out))
        _bridgingRequests = new BridgeRequestList(in, stage, out, _bridgingRequests)
    override def becomeSubRegionOf(parent: Region): Unit =
      this.parent match {
        case null =>
          runContext.impl.becomeSubContextOf(parent.runContext)
          this.parent = parent
          if (! _dispatcherId.isNullOrEmpty) verifyDispatcherAlignmentWithParent()
        case `parent` => // nothing to do
        case _        => throw new IllegalStreamSetupException("A sub-stream must not have more than one parent")
      }
    private[Region] override def assignedDispatcherId: String = _dispatcherId
    override def requestDispatcherAssignment(id: String): Unit =
      _dispatcherId match {
        case null | "" =>
          _dispatcherId = id
          runContext.impl.markAsync()
          if (parent ne null) verifyDispatcherAlignmentWithParent()
        case x => if (x.nonEmpty && x != id) failConflictingAssignments(x, id)
      }
    private def verifyDispatcherAlignmentWithParent(): Unit =
      if (_dispatcherId != parent.impl.assignedDispatcherId) {
        throw new IllegalAsyncBoundaryException(
          "An asynchronous sub-stream with a non-default dispatcher assignment (in this case `" +
            _dispatcherId + "`) must be fenced off from its parent stream with explicit async boundaries!")
      }

    override def processBridgingRequests(): Unit = {
      @tailrec def rec(remaining: BridgeRequestList): Unit =
        if (remaining ne null) {
          val stage    = remaining.stage
          val inStage  = remaining.in.stageImpl
          val outStage = remaining.out.stageImpl
          if (!inStage.hasOutport(outStage) && !outStage.hasInport(inStage)) {
            inStage.rewireOut(stage, outStage)
            outStage.rewireIn(stage, inStage)
            stage.stop()

            // we also need to fix the remaining bridge requests
            @tailrec def rec0(current: BridgeRequestList): Unit =
              if (current ne null) {
                if (current.in eq stage) current.in = inStage
                if (current.out eq stage) current.out = outStage
                rec0(current.tail)
              }
            rec0(remaining.tail)
          }
          rec(remaining.tail)
        }
      rec(_bridgingRequests)
    }

    override def start(async: Boolean): Unit = {
      requireState(stagesTotalCount > 0, toString)
      if (async) {
        val runner = createStreamRunner()
        _impl = new AsyncRunning(parent, runner, stagesTotalCount)
        try {
          val signalQueue = ref.getAndSet(Busy).asInstanceOf[SignalQueue]
          runner.enqueue(new StreamRunner.Message.Start(_needXStart))
          if (signalQueue ne null) signalQueue.dispatch(self, runner)
        } finally ref.set(runner)
      } else {
        requireState(_dispatcherId eq null)
        _impl = new SyncRunning(parent, stagesTotalCount)
        val signalQueue = ref.getAndSet(Sync).asInstanceOf[SignalQueue]
        startSyncStages(_needXStart)
        if (signalQueue ne null) signalQueue.dispatch(self, Sync)
      }
    }
    @tailrec private def startSyncStages(remaining: List[StageImpl]): Unit =
      if (remaining ne Nil) {
        remaining.head.xStart()
        startSyncStages(remaining.tail)
      }
    private def createStreamRunner(): StreamRunner =
      if (parent eq null) {
        val dispatcher =
          if (_dispatcherId.isNullOrEmpty) runContext.env.defaultDispatcher
          else runContext.env.dispatchers(_dispatcherId)
        new StreamRunner(dispatcher, runContext.env)
      } else parent.impl.runner
  }

  private[swave] final class SyncRunning(val parent: Region, val stagesTotalCount: Int) extends Impl {
    def state             = Stage.Region.State.SyncRunning
    var stagesActiveCount = stagesTotalCount
    override def registerStageStopped(): Unit = {
      stagesActiveCount -= 1
      if (stagesActiveCount == 0) {
        _impl = new Stopped(parent, stagesTotalCount)
        runContext.impl.registerRegionStopped()
      }
    }
    override def scheduleSubStreamStartTimeout(stage: StageImpl): Cancellable =
      runContext.env.settings.subStreamStartTimeout match {
        case d: FiniteDuration ⇒ runContext.impl.scheduleSyncSubStreamStartCleanup(stage, d)
        case _                 ⇒ Cancellable.Inactive
      }

    override def enqueueSubscribeInterception(target: StageImpl, from: Outport): Unit =
      runContext.impl.enqueueSyncSubscribeInterception(target, from)
    override def enqueueRequestInterception(target: StageImpl, n: Int, from: Outport): Unit =
      runContext.impl.enqueueSyncRequestInterception(target, n, from)
    override def enqueueCancelInterception(target: StageImpl, from: Outport): Unit =
      runContext.impl.enqueueSyncCancelInterception(target, from)
    override def enqueueOnSubscribeInterception(target: StageImpl, from: Inport): Unit =
      runContext.impl.enqueueSyncOnSubscribeInterception(target, from)
    override def enqueueOnNextInterception(target: StageImpl, elem: AnyRef, from: Inport): Unit =
      runContext.impl.enqueueSyncOnNextInterception(target, elem, from)
    override def enqueueOnCompleteInterception(target: StageImpl, from: Inport): Unit =
      runContext.impl.enqueueSyncOnCompleteInterception(target, from)
    override def enqueueOnErrorInterception(target: StageImpl, e: Throwable, from: Inport): Unit =
      runContext.impl.enqueueSyncOnErrorInterception(target, e, from)
    override def enqueueXEventInterception(target: StageImpl, ev: AnyRef): Unit =
      runContext.impl.enqueueSyncXEventInterception(target, ev)
  }

  private[swave] final class AsyncRunning(val parent: Region,
                                          private[Region] override val runner: StreamRunner,
                                          val stagesTotalCount: Int)
      extends Impl {
    private[this] val _stagesActiveCount                      = new AtomicInteger(stagesTotalCount)
    def stagesActiveCount: Int                                = _stagesActiveCount.get()
    def state                                                 = Stage.Region.State.AsyncRunning(runner)
    private[Region] override def assignedDispatcherId: String = runner.assignedDispatcherId
    override def requestDispatcherAssignment(id: String): Unit =
      if (id.length > 0 && id != assignedDispatcherId) failConflictingAssignments(id, assignedDispatcherId)
    override def registerStageStopped(): Unit =
      if (_stagesActiveCount.decrementAndGet() == 0) {
        _impl = new Stopped(parent, stagesTotalCount)
        runContext.impl.registerRegionStopped()
      }
    override def scheduleTimeout(target: StageImpl, delay: FiniteDuration): Cancellable =
      runner.scheduleTimeout(target, delay)
    override def scheduleSubStreamStartTimeout(stage: StageImpl): Cancellable =
      runContext.env.settings.subStreamStartTimeout match {
        case d: FiniteDuration ⇒ runner.scheduleEvent(stage, d, RunContext.SubStreamStartTimeout)
        case _                 ⇒ Cancellable.Inactive
      }

    override def enqueueSubscribeInterception(target: StageImpl, from: Outport): Unit =
      runner.interceptionLoop.enqueueSubscribe(target, from)
    override def enqueueRequestInterception(target: StageImpl, n: Int, from: Outport): Unit =
      runner.interceptionLoop.enqueueRequest(target, n, from)
    override def enqueueCancelInterception(target: StageImpl, from: Outport): Unit =
      runner.interceptionLoop.enqueueCancel(target, from)
    override def enqueueOnSubscribeInterception(target: StageImpl, from: Inport): Unit =
      runner.interceptionLoop.enqueueOnSubscribe(target, from)
    override def enqueueOnNextInterception(target: StageImpl, elem: AnyRef, from: Inport): Unit =
      runner.interceptionLoop.enqueueOnNext(target, elem, from)
    override def enqueueOnCompleteInterception(target: StageImpl, from: Inport): Unit =
      runner.interceptionLoop.enqueueOnComplete(target, from)
    override def enqueueOnErrorInterception(target: StageImpl, e: Throwable, from: Inport): Unit =
      runner.interceptionLoop.enqueueOnError(target, e, from)
    override def enqueueXEventInterception(target: StageImpl, ev: AnyRef): Unit =
      runner.interceptionLoop.enqueueXEvent(target, ev)
  }

  private[swave] final class Stopped(val parent: Region, val stagesTotalCount: Int) extends Impl {
    def stagesActiveCount: Int = 0
    def state                  = Stage.Region.State.Stopped

    def registerStageStopped(): Unit = ()

    override def enqueueSubscribeInterception(target: StageImpl, from: Outport): Unit            = ()
    override def enqueueRequestInterception(target: StageImpl, n: Int, from: Outport): Unit      = ()
    override def enqueueCancelInterception(target: StageImpl, from: Outport): Unit               = ()
    override def enqueueOnSubscribeInterception(target: StageImpl, from: Inport): Unit           = ()
    override def enqueueOnNextInterception(target: StageImpl, elem: AnyRef, from: Inport): Unit  = ()
    override def enqueueOnCompleteInterception(target: StageImpl, from: Inport): Unit            = ()
    override def enqueueOnErrorInterception(target: StageImpl, e: Throwable, from: Inport): Unit = ()
    override def enqueueXEventInterception(target: StageImpl, ev: AnyRef): Unit                  = ()
  }

  private def failConflictingAssignments(a: String, b: String) =
    throw new IllegalAsyncBoundaryException(s"Conflicting dispatcher assignment to async region: [$a] vs. [$b]")
}

private[impl] object Region {

  private val Busy = new AnyRef
  private val Sync = new AnyRef

  private final class BridgeRequestList(var in: Inport,
                                        var stage: StageImpl,
                                        var out: Outport,
                                        tail: BridgeRequestList)
      extends ImsiList[BridgeRequestList](tail)

  private final class SignalQueue(initialBufferSize: Int) {

    private[this] val signalBuffer: ResizableRingBuffer[AnyRef] =
      new ResizableRingBuffer(initialBufferSize, initialBufferSize << 4)

    def enqueueRequest(target: StageImpl, n: Long, from: Outport): Unit =
      store(target, Statics._0, new java.lang.Long(n), from)

    def enqueueCancel(target: StageImpl, from: Outport): Unit =
      store(target, Statics._1, from)

    def enqueueOnComplete(target: StageImpl, from: Inport): Unit =
      store(target, Statics._2, from)

    def enqueueOnError(target: StageImpl, e: Throwable, from: Inport): Unit =
      store(target, Statics._3, e, from)

    def enqueueXEvent(target: StageImpl, ev: AnyRef): Unit =
      store(target, Statics._4, ev)

    private def store(a: AnyRef, b: AnyRef, c: AnyRef): Unit =
      if (!signalBuffer.write(a, b, c)) throwBufOverflow()

    private def store(a: AnyRef, b: AnyRef, c: AnyRef, d: AnyRef): Unit =
      if (!signalBuffer.write(a, b, c, d)) throwBufOverflow()

    private def throwBufOverflow() = throw new IllegalStateException("Signal queue overflow")

    @tailrec def dispatch(region: Region, channel: AnyRef): Unit =
      if (signalBuffer.nonEmpty) {
        def read()      = signalBuffer.unsafeRead_NoZero()
        def readInt()   = read().asInstanceOf[java.lang.Integer].intValue()
        def readLong()  = read().asInstanceOf[java.lang.Long].longValue()
        def readStage() = read().asInstanceOf[StageImpl]
        val target      = readStage()
        (readInt(): @switch) match {
          case 0 ⇒ region.dispatchRequest(channel, target, readLong(), readStage())
          case 1 ⇒ region.dispatchCancel(channel, target, readStage())
          case 2 ⇒ region.dispatchOnComplete(channel, target, readStage())
          case 3 ⇒ region.dispatchOnError(channel, target, read().asInstanceOf[Throwable], readStage())
          case 4 ⇒ region.dispatchXEvent(channel, target, read())
        }
        dispatch(region, channel)
      }
  }
}
