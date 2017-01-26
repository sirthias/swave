/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.FiniteDuration
import swave.core.impl.util.AbstractInportList
import swave.core.impl.stages.StageImpl
import swave.core.macros._
import swave.core.util._
import swave.core._

private[swave] final class RunContext private (val env: StreamEnv) { self =>
  import RunContext._

  @volatile private var _impl: Impl = new PreStart
  def impl: Impl                    = _impl

  override def toString: String = s"RunContext($impl)"

  private[swave] sealed abstract class Impl {
    def regions: List[Region]

    // PreStart only
    def registerForSealing(stage: StageImpl): Unit   = `n/a`
    def seal(): Unit                                 = `n/a`
    def markAsync(): Unit                            = `n/a`
    def enablePartialRun(): Unit                     = `n/a`
    def becomeSubContextOf(parent: RunContext): Unit = `n/a`
    def start(): Unit                                = `n/a`

    // PreStart + SyncRunning
    def registerForSyncPostRunEvent(stage: StageImpl): Unit = `n/a`

    // SyncRunning
    def enqueueSyncSubscribeInterception(target: StageImpl, from: Outport): Unit            = `n/a`
    def enqueueSyncRequestInterception(target: StageImpl, n: Int, from: Outport): Unit      = `n/a`
    def enqueueSyncCancelInterception(target: StageImpl, from: Outport): Unit               = `n/a`
    def enqueueSyncOnSubscribeInterception(target: StageImpl, from: Inport): Unit           = `n/a`
    def enqueueSyncOnNextInterception(target: StageImpl, elem: AnyRef, from: Inport): Unit  = `n/a`
    def enqueueSyncOnCompleteInterception(target: StageImpl, from: Inport): Unit            = `n/a`
    def enqueueSyncOnErrorInterception(target: StageImpl, e: Throwable, from: Inport): Unit = `n/a`
    def enqueueSyncXEventInterception(target: StageImpl, ev: AnyRef): Unit                  = `n/a`
    def scheduleSyncSubStreamStartCleanup(stage: StageImpl, d: FiniteDuration): Cancellable = `n/a`

    // running only
    def runInterceptionLoop(): Unit                                       = `n/a`
    def regionsActiveCount: Int                                           = `n/a`
    def registerRegionStopped(): Unit                                     = `n/a`
    def termination: Future[Unit]                                         = `n/a`
    def isAsync: Boolean                                                  = `n/a`
    final def isSync: Boolean                                             = !isAsync
    protected[RunContext] def registerSubContext(ctx: RunContext): Unit   = `n/a`
    protected[RunContext] def unregisterSubContext(ctx: RunContext): Unit = `n/a`
    protected[RunContext] def signalTermination(): Unit                   = `n/a`

    private def `n/a` = throw new IllegalStateException(toString)
  }

  private[swave] final class PreStart extends Impl {
    private[this] var remainingToBeSealed        = List.empty[StageImpl]
    private[this] var parent: RunContext         = _
    private[this] var syncNeedPostRun            = List.empty[StageImpl]
    private[this] var partialRunEnabled: Boolean = _
    private[this] var _isAsync: Boolean          = _
    var regions                                  = List.empty[Region]

    override def registerForSealing(stage: StageImpl): Unit = remainingToBeSealed ::= stage
    @tailrec override def seal(): Unit =
      if (remainingToBeSealed ne Nil) {
        val head = remainingToBeSealed.head
        remainingToBeSealed = remainingToBeSealed.tail
        if (!head.isSealed) {
          val region = new Region(head, self)
          regions ::= region
          head.xSeal(region)
        }
        seal()
      }
    override def markAsync(): Unit =
      if ((parent ne null) && parent.impl.isSync) failAsyncSubInSyncParent() else _isAsync = true
    override def enablePartialRun(): Unit = partialRunEnabled = true
    override def becomeSubContextOf(parent: RunContext): Unit =
      this.parent match {
        case null =>
          if (parent.env ne env)
            throw new IllegalStreamSetupException(
              "All sub streams of a stream run must use the same `StreamEnv` instance")
          if (parent.impl.isSync && _isAsync) failAsyncSubInSyncParent()
          _isAsync = parent.impl.isAsync
          this.parent = parent
        case `parent` => // nothing to do
        case _        => throw new IllegalStateException()
      }
    override def registerForSyncPostRunEvent(stage: StageImpl): Unit = {
      requireState(!_isAsync)
      syncNeedPostRun ::= stage
    }
    override def start(): Unit = {
      if (_isAsync) {
        _impl = if (parent eq null) new AsyncMainRunning(regions) else new AsyncSubRunning(parent, regions)
        doStart()
      } else if (parent eq null) {
        val imp = new SyncMainRunning(regions, syncNeedPostRun)
        _impl = imp
        doStart()
        imp.doRunInterceptionLoop()
        imp.postRun()
        if (!partialRunEnabled && !imp.isTerminated) {
          throw new UnterminatedSynchronousStreamException
        } else imp.enableExternalRun()
      } else {
        if (syncNeedPostRun ne Nil) syncNeedPostRun.foreach(parent.impl.registerForSyncPostRunEvent)
        _impl = new SyncSubRunning(parent, regions)
        doStart()
      }
    }
    private def doStart(): Unit = {
      @tailrec def startRegions(remaining: List[Region]): Unit =
        if (remaining ne Nil) {
          remaining.head.impl.start()
          startRegions(remaining.tail)
        }

      if (parent ne null) parent.impl.registerSubContext(self)
      startRegions(regions)
    }

    override def toString: String =
      s"PreStart(parent:${parent ne null}, isAsync=${_isAsync}, regions:${regions.size})"
  }

  private[swave] sealed abstract class SyncRunning(final override val regions: List[Region]) extends Impl {
    private[this] var subContexts: List[RunContext] = Nil
    private[this] var _regionsActiveCount           = regions.size
    final override def regionsActiveCount           = _regionsActiveCount
    final override def isAsync                      = false
    final override def registerRegionStopped(): Unit = {
      _regionsActiveCount -= 1
      signalTerminationIfNecessary()
    }
    final override def registerSubContext(ctx: RunContext): Unit = subContexts ::= ctx
    final override def unregisterSubContext(ctx: RunContext): Unit = {
      val updated = subContexts.remove(ctx)
      requireState(updated ne subContexts)
      subContexts = updated
      signalTerminationIfNecessary()
    }
    private def signalTerminationIfNecessary(): Unit =
      if (_regionsActiveCount == 0 && (subContexts eq Nil)) signalTermination()
  }

  private[swave] final class SyncMainRunning(regs: List[Region], npr: List[StageImpl]) extends SyncRunning(regs) {
    private[this] var needPostRun: List[StageImpl] = npr
    private[this] var cleanUp: List[Runnable]      = Nil
    private[this] var _termination: AnyRef         = _ // null: unterminated, self: terminated, Promise[_]: promise
    private[this] var externalRunEnabled: Boolean  = _
    private[this] val interceptionLoop             = new InterceptionLoop(env.settings.maxBatchSize)

    def enableExternalRun(): Unit                                    = externalRunEnabled = true
    override def registerForSyncPostRunEvent(stage: StageImpl): Unit = needPostRun ::= stage

    override def enqueueSyncSubscribeInterception(target: StageImpl, from: Outport): Unit =
      interceptionLoop.enqueueSubscribe(target, from)
    override def enqueueSyncRequestInterception(target: StageImpl, n: Int, from: Outport): Unit =
      interceptionLoop.enqueueRequest(target, n, from)
    override def enqueueSyncCancelInterception(target: StageImpl, from: Outport): Unit =
      interceptionLoop.enqueueCancel(target, from)
    override def enqueueSyncOnSubscribeInterception(target: StageImpl, from: Inport): Unit =
      interceptionLoop.enqueueOnSubscribe(target, from)
    override def enqueueSyncOnNextInterception(target: StageImpl, elem: AnyRef, from: Inport): Unit =
      interceptionLoop.enqueueOnNext(target, elem, from)
    override def enqueueSyncOnCompleteInterception(target: StageImpl, from: Inport): Unit =
      interceptionLoop.enqueueOnComplete(target, from)
    override def enqueueSyncOnErrorInterception(target: StageImpl, e: Throwable, from: Inport): Unit =
      interceptionLoop.enqueueOnError(target, e, from)
    override def enqueueSyncXEventInterception(target: StageImpl, ev: AnyRef): Unit =
      interceptionLoop.enqueueXEvent(target, ev)

    def isTerminated: Boolean =
      _termination match {
        case null          => false
        case `self`        => true
        case x: Promise[_] => x.isCompleted
      }
    override def termination: Future[Unit] =
      _termination match {
        case null          => { val p = Promise[Unit](); _termination = p; p.future }
        case `self`        => Future.successful(())
        case x: Promise[_] => x.future.asInstanceOf[Future[Unit]]
      }
    override def signalTermination(): Unit =
      _termination match {
        case null          => _termination = self
        case x: Promise[_] => { x.asInstanceOf[Promise[Unit]].success(()); () }
      }
    override def scheduleSyncSubStreamStartCleanup(stage: StageImpl, d: FiniteDuration): Cancellable = {
      val timer =
        new Cancellable with Runnable {
          def run(): Unit          = stage.xEvent(SubStreamStartTimeout)
          def stillActive: Boolean = cleanUp contains this
          def cancel(): Boolean = {
            val x = cleanUp.remove(this)
            (x ne cleanUp) && { cleanUp = x; true }
          }
        }
      cleanUp ::= timer
      timer
    }

    override def runInterceptionLoop(): Unit =
      if (externalRunEnabled) doRunInterceptionLoop()

    @tailrec def doRunInterceptionLoop(): Unit =
      if (interceptionLoop.hasInterception) {
        interceptionLoop.handleInterception()
        doRunInterceptionLoop()
      }

    def postRun(): Unit = {
      @tailrec def dispatchPostRunSignals(): Unit =
        if (needPostRun ne Nil) {
          val stages = needPostRun
          needPostRun = Nil // allow for re-registration in signal handler

          @tailrec def rec(remaining: List[StageImpl]): Unit =
            if (remaining ne Nil) {
              remaining.head.xEvent(PostRun)
              doRunInterceptionLoop()
              rec(remaining.tail)
            }
          rec(stages)
          dispatchPostRunSignals()
        }

      @tailrec def runCleanups(remaining: List[Runnable]): Unit =
        if (remaining ne Nil) {
          remaining.head.run()
          runCleanups(remaining.tail)
        }

      dispatchPostRunSignals()
      runCleanups(cleanUp)
    }
    override def toString: String = s"SyncMainRunning(regions:${regions.size})"
  }

  private[swave] final class SyncSubRunning(parent: RunContext, regs: List[Region]) extends SyncRunning(regs) {
    override def runInterceptionLoop(): Unit = parent.impl.runInterceptionLoop()
    override def termination: Future[Unit]   = parent.impl.termination
    override def registerForSyncPostRunEvent(stage: StageImpl): Unit =
      parent.impl.registerForSyncPostRunEvent(stage)
    override def enqueueSyncSubscribeInterception(target: StageImpl, from: Outport): Unit =
      parent.impl.enqueueSyncSubscribeInterception(target, from)
    override def enqueueSyncRequestInterception(stage: StageImpl, n: Int, from: Outport): Unit =
      parent.impl.enqueueSyncRequestInterception(stage, n, from)
    override def enqueueSyncCancelInterception(target: StageImpl, from: Outport): Unit =
      parent.impl.enqueueSyncCancelInterception(target, from)
    override def enqueueSyncOnSubscribeInterception(target: StageImpl, from: Inport): Unit =
      parent.impl.enqueueSyncOnSubscribeInterception(target, from)
    override def enqueueSyncOnNextInterception(target: StageImpl, elem: AnyRef, from: Inport): Unit =
      parent.impl.enqueueSyncOnNextInterception(target, elem, from)
    override def enqueueSyncOnCompleteInterception(target: StageImpl, from: Inport): Unit =
      parent.impl.enqueueSyncOnCompleteInterception(target, from)
    override def enqueueSyncOnErrorInterception(target: StageImpl, e: Throwable, from: Inport): Unit =
      parent.impl.enqueueSyncOnErrorInterception(target, e, from)
    override def enqueueSyncXEventInterception(target: StageImpl, ev: AnyRef): Unit =
      parent.impl.enqueueSyncXEventInterception(target, ev)

    override def scheduleSyncSubStreamStartCleanup(stage: StageImpl, d: FiniteDuration): Cancellable =
      parent.impl.scheduleSyncSubStreamStartCleanup(stage, d)
    override def signalTermination(): Unit =
      parent.impl.unregisterSubContext(self)
    override def toString: String = s"SyncSubRunning(regions:${regions.size})"
  }

  private[swave] sealed abstract class AsyncRunning(final override val regions: List[Region]) extends Impl {
    private[this] val _subContexts        = new AtomicReference(List.empty[RunContext])
    private[this] val _regionsActiveCount = new AtomicInteger(regions.size)
    final override def regionsActiveCount = _regionsActiveCount.get()
    final override def isAsync            = true
    final override def registerRegionStopped(): Unit =
      signalTerminationIfNecessary(_regionsActiveCount.decrementAndGet(), _subContexts.get)
    @tailrec final override def registerSubContext(ctx: RunContext): Unit = {
      val current = _subContexts.get()
      val updated = ctx :: current
      if (!_subContexts.compareAndSet(current, updated)) registerSubContext(ctx) // another thread interfered, so retry
    }
    @tailrec final override def unregisterSubContext(ctx: RunContext): Unit = {
      val current = _subContexts.get
      val updated = current.remove(ctx)
      requireState(updated ne current)
      if (_subContexts.compareAndSet(current, updated)) signalTerminationIfNecessary(regionsActiveCount, updated)
      else unregisterSubContext(ctx) // another thread interfered, so retry
    }
    private def signalTerminationIfNecessary(activeCount: Int, subContexts: List[RunContext]): Unit =
      if (activeCount == 0 && (subContexts eq Nil)) signalTermination()

    final override def runInterceptionLoop(): Unit = ()
  }

  private[swave] final class AsyncMainRunning(regs: List[Region]) extends AsyncRunning(regs) {
    private[this] val terminationPromise   = Promise[Unit]()
    override def termination: Future[Unit] = terminationPromise.future
    override def signalTermination(): Unit = { terminationPromise.success(()); () }
    override def toString: String          = s"AsyncMainRunning(regions:${regions.size})"
  }

  private[swave] final class AsyncSubRunning(parent: RunContext, regs: List[Region]) extends AsyncRunning(regs) {
    override def termination: Future[Unit] = parent.impl.termination
    override def signalTermination(): Unit = parent.impl.unregisterSubContext(self)
    override def toString: String          = s"AsyncSubRunning(regions:${regions.size})"
  }

  private def failAsyncSubInSyncParent() =
    throw new IllegalAsyncBoundaryException(
      "A synchronous parent stream must not contain an async sub-stream. " +
        "You can fix this by explicitly marking the parent stream as `async`.")
}

private[swave] object RunContext {

  case object PostRun
  case object SubStreamStartTimeout
  final case class Timeout(timer: Cancellable)

  private final class QueuedRequestList(in: StageImpl, tail: QueuedRequestList, val n: Long, val from: Outport)
      extends AbstractInportList[QueuedRequestList](in, tail)

  /**
    * Seals the stream by sending an `xSeal` signal through the stream graph starting from the given stage.
    */
  def seal(stage: StageImpl, env: StreamEnv): RunContext = {
    if (stage.isSealed) {
      val msg = stage + " is already sealed. It cannot be sealed a second time. " +
          "Are you trying to reuse a Spout, Drain, Pipe or Module?"
      throw new IllegalReuseException(msg)
    }
    val ctx = new RunContext(env)
    val imp = ctx.impl
    imp.registerForSealing(stage)
    imp.seal()
    ctx
  }
}
