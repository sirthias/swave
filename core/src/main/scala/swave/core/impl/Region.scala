/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.FiniteDuration
import scala.annotation.tailrec
import swave.core.impl.stages.StageImpl
import swave.core.macros._
import swave.core.util._
import swave.core._

private[swave] final class Region private[impl] (val entryPoint: StageImpl, val runContext: RunContext)
    extends Stage.Region { self =>

  val mbs: Int = runContext.env.settings.maxBatchSize

  @volatile private var _impl: Impl = new PreStart
  def impl: Impl                    = _impl

  def env: StreamEnv            = runContext.env
  def state: Stage.Region.State = impl.state
  def parent: Option[Region]    = Option(impl.parent)
  def stagesTotalCount: Int     = impl.stagesTotalCount
  def stagesActiveCount: Int    = impl.stagesActiveCount

  private[impl] def sealAndStart(stage: StageImpl): Unit = {
    val ctx = RunContext.seal(stage, env)
    ctx.impl.start()
  }

  override def toString: String = s"Region($state)@${Integer.toHexString(System.identityHashCode(this))}"

  private[swave] sealed abstract class Impl {
    def state: Stage.Region.State
    def parent: Region
    def stagesTotalCount: Int
    def stagesActiveCount: Int

    def enqueueRequest(target: StageImpl, n: Long, from: Outport): Unit
    def enqueueCancel(target: StageImpl, from: Outport): Unit
    def enqueueOnNext(target: StageImpl, elem: AnyRef, from: Inport): Unit
    def enqueueOnComplete(target: StageImpl, from: Inport): Unit
    def enqueueOnError(target: StageImpl, e: Throwable, from: Inport): Unit
    def enqueueXEvent(target: StageImpl, ev: AnyRef): Unit

    // PreStart only
    def becomeSubRegionOf(parent: Region): Unit   = `n/a`
    def registerForXStart(stage: StageImpl): Unit = `n/a`
    def registerStageSealed(): Unit               = `n/a`
    def start(): Unit                             = `n/a`

    // PreStart + AsyncRunning
    private[Region] def assignedDispatcherId: String                 = `n/a` // none (null), default (empty) or named (non-empty)
    private[impl] def requestDispatcherAssignment(dispatcherId: String = ""): Unit = `n/a`

    // SyncRunning + AsyncRunning
    private[impl] def registerStageStopped(): Unit                                 = ()
    private[impl] def scheduleSubStreamStartTimeout(stage: StageImpl): Cancellable = `n/a`
    private[impl] def enqueueSubscribeInterception(target: StageImpl, from: Outport): Unit = `n/a`
    private[impl] def enqueueRequestInterception(target: StageImpl, n: Int, from: Outport): Unit = `n/a`
    private[impl] def enqueueCancelInterception(target: StageImpl, from: Outport): Unit = `n/a`
    private[impl] def enqueueOnSubscribeInterception(target: StageImpl, from: Inport): Unit = `n/a`
    private[impl] def enqueueOnNextInterception(target: StageImpl, elem: AnyRef, from: Inport): Unit = `n/a`
    private[impl] def enqueueOnCompleteInterception(target: StageImpl, from: Inport): Unit = `n/a`
    private[impl] def enqueueOnErrorInterception(target: StageImpl, e: Throwable, from: Inport): Unit = `n/a`
    private[impl] def enqueueXEventInterception(target: StageImpl, ev: AnyRef): Unit = `n/a`

    // AsyncRunning only
    private[Region] def runner: StreamRunner                                   = `n/a`
    private[impl] def scheduleTimeout(target: StageImpl, delay: FiniteDuration): Cancellable = `n/a`

    protected final def `n/a` = throw new IllegalStateException(toString)
  }

  private[swave] final class PreStart extends Impl {
    private[this] var _dispatcherId: String                = _
    private[this] var _needXStart: List[StageImpl]         = Nil
    private[this] var _queuedRequests: List[(StageImpl, Long, Outport)] = Nil
    def state                                              = Stage.Region.State.PreStart
    var parent: Region                                     = _
    var stagesTotalCount                                   = 0
    def stagesActiveCount                                  = 0

    def enqueueRequest(target: StageImpl, n: Long, from: Outport): Unit = _queuedRequests ::= ((target, n, from))
    def enqueueCancel(target: StageImpl, from: Outport): Unit = `n/a`
    def enqueueOnNext(target: StageImpl, elem: AnyRef, from: Inport): Unit = `n/a`
    def enqueueOnComplete(target: StageImpl, from: Inport): Unit = `n/a`
    def enqueueOnError(target: StageImpl, e: Throwable, from: Inport): Unit = `n/a`
    def enqueueXEvent(target: StageImpl, ev: AnyRef): Unit = `n/a`

    override def registerForXStart(stage: StageImpl): Unit = _needXStart ::= stage
    override def registerStageSealed(): Unit               = stagesTotalCount += 1
    override def becomeSubRegionOf(parent: Region): Unit =
      this.parent match {
        case null =>
          runContext.impl.becomeSubContextOf(parent.runContext)
          this.parent = parent
          if (!_dispatcherId.isNullOrEmpty) verifyDispatcherAlignmentWithParent()
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
    override def start(): Unit = {
      requireState(stagesTotalCount > 0, toString)
      if (runContext.impl.isAsync) {
        val runner = createStreamRunner()
        _impl = new AsyncRunning(parent, runner, stagesTotalCount)
        runner.enqueue(new StreamRunner.Message.Start(_needXStart))
      } else {
        requireState(_dispatcherId eq null)
        startSync()
      }
      if (_queuedRequests ne Nil) {
        val imp = _impl
        _queuedRequests.foreach { case (target, n, from) => imp.enqueueRequest(target, n, from) }
      }
    }
    private def startSync(): Unit = {
      _impl = new SyncRunning(parent, stagesTotalCount)
      @tailrec def rec(remaining: List[StageImpl]): Unit =
        if (remaining ne Nil) {
          remaining.head.xStart()
          rec(remaining.tail)
        }
      rec(_needXStart)
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

    def enqueueRequest(target: StageImpl, n: Long, from: Outport): Unit = target.request(n)(from)
    def enqueueCancel(target: StageImpl, from: Outport): Unit = target.cancel()(from)
    def enqueueOnNext(target: StageImpl, elem: AnyRef, from: Inport): Unit = target.onNext(elem)(from)
    def enqueueOnComplete(target: StageImpl, from: Inport): Unit = target.onComplete()(from)
    def enqueueOnError(target: StageImpl, e: Throwable, from: Inport): Unit = target.onError(e)(from)
    def enqueueXEvent(target: StageImpl, ev: AnyRef): Unit = target.xEvent(ev)

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

    def enqueueRequest(target: StageImpl, n: Long, from: Outport): Unit =
      runner.enqueue(new StreamRunner.Message.Request(target, n, from))
    def enqueueCancel(target: StageImpl, from: Outport): Unit =
      runner.enqueue(new StreamRunner.Message.Cancel(target, from))
    def enqueueOnNext(target: StageImpl, elem: AnyRef, from: Inport): Unit =
      runner.enqueue(new StreamRunner.Message.OnNext(target, elem, from))
    def enqueueOnComplete(target: StageImpl, from: Inport): Unit =
      runner.enqueue(new StreamRunner.Message.OnComplete(target, from))
    def enqueueOnError(target: StageImpl, e: Throwable, from: Inport): Unit =
      runner.enqueue(new StreamRunner.Message.OnError(target, e, from))
    def enqueueXEvent(target: StageImpl, ev: AnyRef): Unit =
      runner.enqueue(new StreamRunner.Message.XEvent(target, ev, runner))

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
    def stagesActiveCount: Int                                              = 0
    def state                                                               = Stage.Region.State.Stopped

    def enqueueRequest(target: StageImpl, n: Long, from: Outport): Unit = ()
    def enqueueCancel(target: StageImpl, from: Outport): Unit = ()
    def enqueueOnNext(target: StageImpl, elem: AnyRef, from: Inport): Unit = ()
    def enqueueOnComplete(target: StageImpl, from: Inport): Unit = ()
    def enqueueOnError(target: StageImpl, e: Throwable, from: Inport): Unit = ()
    def enqueueXEvent(target: StageImpl, ev: AnyRef): Unit = ()

    override def enqueueSubscribeInterception(target: StageImpl, from: Outport): Unit            = ()
    override def enqueueRequestInterception(target: StageImpl, n: Int, from: Outport): Unit   = ()
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
