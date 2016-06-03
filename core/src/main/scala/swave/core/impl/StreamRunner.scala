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
import swave.core.PipeElem.InOut.AsyncBoundary
import swave.core.{ Dispatcher, PipeElem, StreamEnv }
import swave.core.impl.stages.Stage
import swave.core.util._
import com.typesafe.scalalogging.Logger

private[impl] final class StreamRunner(_throughput: Int, _log: Logger, _dispatcher: Dispatcher)
    extends StreamActor(_throughput, _log, _dispatcher) {
  import StreamRunner._

  type MessageType = Message

  startMessageProcessing()

  def enqueueXStart(target: Stage) = enqueue(new Message.XStart(target))
  def enqueueSubscribe(target: Stage, from: Outport) = enqueue(new Message.Subscribe(target, from))
  def enqueueRequest(target: Stage, n: Long, from: Outport) = enqueue(new Message.Request(target, n, from))
  def enqueueCancel(target: Stage, from: Outport) = enqueue(new Message.Cancel(target, from))
  def enqueueOnSubscribe(target: Stage, from: Inport) = enqueue(new Message.OnSubscribe(target, from))
  def enqueueOnNext(target: Stage, elem: AnyRef, from: Inport) = enqueue(new Message.OnNext(target, elem, from))
  def enqueueOnComplete(target: Stage, from: Inport) = enqueue(new Message.OnComplete(target, from))
  def enqueueOnError(target: Stage, error: Throwable, from: Inport) = enqueue(new Message.OnError(target, error, from))

  protected def receive(msg: Message): Unit = {
    val target = msg.target
    msg.id match {
      case 0 ⇒ target.xStart()
      case 1 ⇒
        val m = msg.asInstanceOf[Message.Subscribe]
        target.subscribe()(m.from)
      case 2 ⇒
        val m = msg.asInstanceOf[Message.Request]
        target.request(m.n)(m.from)
      case 3 ⇒
        val m = msg.asInstanceOf[Message.Cancel]
        target.cancel()(m.from)
      case 4 ⇒
        val m = msg.asInstanceOf[Message.OnSubscribe]
        target.onSubscribe()(m.from)
      case 5 ⇒
        val m = msg.asInstanceOf[Message.OnNext]
        target.onNext(m.elem)(m.from)
      case 6 ⇒
        val m = msg.asInstanceOf[Message.OnComplete]
        target.onComplete()(m.from)
      case 7 ⇒
        val m = msg.asInstanceOf[Message.OnError]
        target.onError(m.error)(m.from)
    }
  }
}

private[impl] object StreamRunner {

  abstract class Message(val id: Int, val target: Stage)
  object Message {
    final class XStart(target: Stage) extends Message(0, target)
    final class Subscribe(target: Stage, val from: Outport) extends Message(1, target)
    final class Request(target: Stage, val n: Long, val from: Outport) extends Message(2, target)
    final class Cancel(target: Stage, val from: Outport) extends Message(3, target)
    final class OnSubscribe(target: Stage, val from: Inport) extends Message(4, target)
    final class OnNext(target: Stage, val elem: AnyRef, val from: Inport) extends Message(5, target)
    final class OnComplete(target: Stage, val from: Inport) extends Message(6, target)
    final class OnError(target: Stage, val error: Throwable, val from: Inport) extends Message(7, target)
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

  def assignRunners(needRunner: StageDispatcherIdListMap, env: StreamEnv): Unit = {

    @tailrec def rec1(remaining: StageList, assignDefault: AssignToRegion): Unit =
      if (remaining.nonEmpty) {
        import remaining._
        if (stage.runner eq null) assignDefault(stage.asInstanceOf[PipeElem.Basic])
        rec1(tail, assignDefault)
      }

    @tailrec def rec0(remaining: StageDispatcherIdListMap, defaultAssignments: StageList,
      cache: Map[String, AssignToRegion]): Unit = {

      def createAssign(dispatcher: Dispatcher) =
        new AssignToRegion(new StreamRunner(env.settings.throughput, env.log, dispatcher))

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
            throw new IllegalStateException("Conflicting dispatcher assignment to async region containing stage " +
              s"'${stage.getClass.getSimpleName}': [${assign.runner.dispatcher.name}] vs. [${stage.runner.dispatcher.name}]")
          }
          rec0(tail, defaultAssignments, nextCache)
        } else rec0(tail, stage +: defaultAssignments, cache)
      } else rec1(defaultAssignments, createAssign(env.defaultDispatcher))
    }

    rec0(needRunner, StageList.empty, Map.empty)
  }

  final class StageDispatcherIdListMap(
    val stage: Stage,
    val dispatcherId: String,
    tail: StageDispatcherIdListMap) extends ImsiList[StageDispatcherIdListMap](tail)

  object StageDispatcherIdListMap {
    def empty: StageDispatcherIdListMap = null
    def apply(stage: Stage, dispatcherId: String, tail: StageDispatcherIdListMap = null) =
      new StageDispatcherIdListMap(stage, dispatcherId, tail)
  }
}