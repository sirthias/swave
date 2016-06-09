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

package swave.core.impl

import scala.annotation.tailrec
import scala.concurrent.duration._
import com.typesafe.scalalogging.Logger
import swave.core.PipeElem.InOut.AsyncBoundary
import swave.core._
import swave.core.impl.stages.Stage
import swave.core.util._

private[core] final class StreamRunner(_throughput: Int, _log: Logger, _dispatcher: Dispatcher, scheduler: Scheduler)
    extends StreamActor(_throughput, _log, _dispatcher) {
  import StreamRunner._

  type MessageType = Message

  startMessageProcessing()

  protected def receive(msg: Message): Unit = {
    val target = msg.target
    msg.id match {
      case 0 ⇒
        val m = msg.asInstanceOf[Message.Subscribe]
        target.subscribe()(m.from)
      case 1 ⇒
        val m = msg.asInstanceOf[Message.Request]
        target.request(m.n)(m.from)
      case 2 ⇒
        val m = msg.asInstanceOf[Message.Cancel]
        target.cancel()(m.from)
      case 3 ⇒
        val m = msg.asInstanceOf[Message.OnSubscribe]
        target.onSubscribe()(m.from)
      case 4 ⇒
        val m = msg.asInstanceOf[Message.OnNext]
        target.onNext(m.elem)(m.from)
      case 5 ⇒
        val m = msg.asInstanceOf[Message.OnComplete]
        target.onComplete()(m.from)
      case 6 ⇒
        val m = msg.asInstanceOf[Message.OnError]
        target.onError(m.error)(m.from)
      case 7 ⇒
        target.xStart()
      case 8 ⇒
        target.xEvent(msg.asInstanceOf[Message.XEvent].ev)
    }
  }

  def scheduleEvent(target: Stage, delay: FiniteDuration, ev: AnyRef): Cancellable = {
    val msg = new Message.XEvent(target, ev, this)
    if (delay > Duration.Zero) {
      // we can run the event Runnable directly on the scheduler thread since
      // all it does is enqueueing the event on the runner's dispatcher
      scheduler.scheduleOnce(delay, msg)(CallingThreadExecutionContext)
    } else {
      msg.run()
      Cancellable.Inactive
    }
  }

  def scheduleTimeout(target: Stage, delay: FiniteDuration): Cancellable =
    scheduleEvent(target, delay, StreamRunner.Timeout)

  override def toString = dispatcher.name + '@' + Integer.toHexString(System.identityHashCode(this))
}

private[core] object StreamRunner {

  case object Timeout

  sealed abstract class Message(val id: Int, val target: Stage)
  object Message {
    final class Subscribe(target: Stage, val from: Outport) extends Message(0, target)
    final class Request(target: Stage, val n: Long, val from: Outport) extends Message(1, target)
    final class Cancel(target: Stage, val from: Outport) extends Message(2, target)
    final class OnSubscribe(target: Stage, val from: Inport) extends Message(3, target)
    final class OnNext(target: Stage, val elem: AnyRef, val from: Inport) extends Message(4, target)
    final class OnComplete(target: Stage, val from: Inport) extends Message(5, target)
    final class OnError(target: Stage, val error: Throwable, val from: Inport) extends Message(6, target)
    final class XStart(target: Stage) extends Message(7, target)
    final class XEvent(target: Stage, val ev: AnyRef, runner: StreamRunner) extends Message(8, target) with Runnable {
      def run() = runner.enqueue(this)
    }
  }

  private final class AssignToRegion(val runner: StreamRunner) extends (PipeElem.Basic ⇒ Unit) {
    def _apply(pe: PipeElem.Basic): Unit = apply(pe)
    @tailrec def apply(pe: PipeElem.Basic): Unit = {
      val stage = pe.asInstanceOf[Stage]
      if ((stage.runner eq null) && !stage.isInstanceOf[AsyncBoundary]) {
        stage.runner = runner
        import pe._
        3 * inputElems.size012 + outputElems.size012 match {
          case 0 /* 0:0 */ ⇒ throw new IllegalStateException // no input and no output?
          case 1 /* 0:1 */ ⇒ apply(outputElems.head)
          case 2 /* 0:x */ ⇒ outputElems.foreach(this)
          case 3 /* 1:0 */ ⇒ apply(inputElems.head)
          case 4 /* 1:1 */ ⇒ { _apply(inputElems.head); apply(outputElems.head) }
          case 5 /* 1:x */ ⇒ { _apply(inputElems.head); outputElems.foreach(this) }
          case 6 /* x:0 */ ⇒ inputElems.foreach(this)
          case 7 /* x:1 */ ⇒ { inputElems.foreach(this); apply(outputElems.head) }
          case 8 /* x:x */ ⇒ { inputElems.foreach(this); outputElems.foreach(this) }
        }
      }
    }
  }

  def assignRunners(needRunner: StageDispatcherIdListMap)(implicit env: StreamEnv): Unit = {

    def createAssign(dispatcher: Dispatcher) =
      new AssignToRegion(new StreamRunner(env.settings.throughput, env.log, dispatcher, env.scheduler))

    @tailrec def assignNonDefaults(remaining: StageDispatcherIdListMap, defaultAssignments: List[Stage],
      cache: Map[String, AssignToRegion]): List[Stage] = {

      if (remaining.nonEmpty) {
        import remaining._
        if (dispatcherId.nonEmpty) {
          var nextCache = cache
          val assign = cache.get(dispatcherId) match {
            case Some(x) ⇒ x
            case None ⇒
              val a = createAssign(env.dispatchers(dispatcherId))
              nextCache = cache.updated(dispatcherId, a)
              a
          }
          if (stage.runner eq null) {
            assign(stage.asInstanceOf[PipeElem.Basic])
          } else if (stage.runner ne assign.runner) {
            throw new IllegalAsyncBoundaryException("Conflicting dispatcher assignment to async region containing stage " +
              s"'${stage.getClass.getSimpleName}': [${assign.runner.dispatcher.name}] vs. [${stage.runner.dispatcher.name}]")
          }
          assignNonDefaults(tail, defaultAssignments, nextCache)
        } else assignNonDefaults(tail, stage :: defaultAssignments, cache)
      } else defaultAssignments
    }

    val defaultAssignments = assignNonDefaults(needRunner, Nil, Map.empty)
    defaultAssignments.foreach { stage ⇒
      if (stage.runner eq null) {
        val assign = createAssign(env.defaultDispatcher)
        assign(stage.asInstanceOf[PipeElem.Basic])
      }
    }
  }

  final class StageDispatcherIdListMap(
    val stage: Stage,
    val dispatcherId: String,
    tail: StageDispatcherIdListMap) extends ImsiList[StageDispatcherIdListMap](tail)
  object StageDispatcherIdListMap {
    def empty: StageDispatcherIdListMap = null
    def apply(stage: Stage, dispatcherId: String, tail: StageDispatcherIdListMap) =
      new StageDispatcherIdListMap(stage, dispatcherId, tail)
  }

  final class StageStageList(val stage0: Stage, val stage1: Stage,
    tail: StageStageList) extends ImsiList[StageStageList](tail)
  object StageStageList {
    def empty: StageStageList = null
    def apply(stage0: Stage, stage1: Stage, tail: StageStageList) =
      new StageStageList(stage0, stage1, tail)
  }
}