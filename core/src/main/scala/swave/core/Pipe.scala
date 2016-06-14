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

package swave.core

import org.reactivestreams.Processor
import scala.annotation.unchecked.{ uncheckedVariance ⇒ uV }
import shapeless._
import swave.core.impl.rs.SubPubProcessor
import swave.core.impl.stages.Stage
import swave.core.impl.stages.inout.NopStage
import swave.core.impl._

final class Pipe[-A, +B] private (
    private val firstStage: Outport,
    private val lastStage: Inport) extends StreamOps[B @uV] {

  type Repr[T] = Pipe[A @uV, T]

  def pipeElem: PipeElem = firstStage.pipeElem

  private[core] def transform(stream: Stream[A]): Stream[B] = {
    stream.inport.subscribe()(firstStage)
    new Stream[B](lastStage)
  }

  protected def base: Inport = lastStage
  protected def wrap: Inport ⇒ Repr[_] = in ⇒ new Pipe(firstStage, in)

  protected[core] def append[T](stage: Stage): Repr[T] = {
    lastStage.subscribe()(stage)
    new Pipe(firstStage, stage)
  }

  def identity: A =>> B = this

  def to[R](drain: Drain[B, R]): Drain[A, R] = {
    lastStage.subscribe()(drain.outport)
    new Drain(firstStage, drain.result)
  }

  def via[C](pipe: B =>> C): Repr[C] = {
    lastStage.subscribe()(pipe.firstStage)
    new Pipe(firstStage, pipe.lastStage)
  }

  def via[P <: HList, R, Out](joined: Module.Joined[B :: HNil, P, R])(
    implicit
    vr: TypeLogic.ViaResult[P, Drain[A @uV, R], Repr @uV, Out]): Out = {
    val out = joined.module(InportList(lastStage))
    val result = vr.id match {
      case 0 ⇒ new Drain(firstStage, out)
      case 1 ⇒ new Pipe(firstStage, out.asInstanceOf[InportList].in)
      case 2 ⇒ new StreamOps.FanIn(out.asInstanceOf[InportList], wrap)
    }
    result.asInstanceOf[Out]
  }

  def toProcessor: Piping[Processor[A @uV, B @uV]] = {
    val (stream, subscriber) = Stream.withSubscriber[A]
    stream.via(this).to(Drain.toPublisher()).mapResult(new SubPubProcessor(subscriber, _))
  }

  def named(name: String): A =>> B = {
    val marker = new ModuleMarker(name)
    marker.markEntry(firstStage)
    marker.markExit(lastStage)
    this
  }

  def named(name: String, otherInput: Stream[_]): A =>> B = {
    val marker = new ModuleMarker(name)
    marker.markEntry(firstStage)
    marker.markEntry(otherInput.inport)
    marker.markExit(lastStage)
    this
  }

  def named(name: String, otherOutput: Drain[_, _]): A =>> B = {
    val marker = new ModuleMarker(name)
    marker.markEntry(firstStage)
    marker.markExit(lastStage)
    marker.markExit(otherOutput.outport)
    this
  }
}

object Pipe {

  def apply[T]: T =>> T = {
    val stage = new NopStage
    new Pipe(stage, stage)
  }
}
