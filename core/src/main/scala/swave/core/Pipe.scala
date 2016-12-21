/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import java.util.concurrent.atomic.AtomicReference
import org.reactivestreams.Processor
import scala.util.control.NonFatal
import scala.annotation.tailrec
import scala.annotation.unchecked.{uncheckedVariance => uV}
import swave.core.impl.rs.SubPubProcessor
import swave.core.impl.util.InportList
import swave.core.impl.stages.StageImpl
import swave.core.impl.stages.inout.NopStage
import swave.core.impl._
import shapeless._

final class Pipe[-A, +B] private (private[core] val firstStage: Outport, private[core] val lastStage: Inport)
    extends StreamOps[B @uV] {

  type Repr[T] = Pipe[A @uV, T]

  def inputAsDrain: Drain[A, Unit] = Drain(firstStage)
  def outputAsSpout: Spout[B]      = new Spout(lastStage)

  private[core] def transform(spout: Spout[A]): Spout[B] = {
    spout.inport.subscribe()(firstStage)
    new Spout(lastStage)
  }

  protected def base: Inport                     = lastStage
  protected def wrap[T](inport: Inport): Repr[T] = new Pipe(firstStage, inport)
  protected def append[T](stage: StageImpl): Repr[T] = {
    lastStage.subscribe()(stage)
    new Pipe(firstStage, stage)
  }

  def to[R](drain: Drain[B, R]): Drain[A, R] = {
    lastStage.subscribe()(drain.outport)
    new Drain(firstStage, drain.result)
  }

  def via[C](pipe: Pipe[B, C]): Repr[C] = {
    lastStage.subscribe()(pipe.firstStage)
    new Pipe(firstStage, pipe.lastStage)
  }

  def via[P <: HList, R, Out](joined: Module.TypeLogic.Joined[B :: HNil, P, R])(
      vr: TypeLogic.ViaResult[P, Drain[A @uV, R], Repr @uV, Out]): Out = {
    val out = ModuleImpl(joined.module)(InportList(lastStage))
    val result = vr.id match {
      case 0 ⇒ new Drain(firstStage, out)
      case 1 ⇒ new Pipe(firstStage, out.asInstanceOf[InportList].in)
      case 2 ⇒ new FanIn(out.asInstanceOf[InportList])
    }
    result.asInstanceOf[Out]
  }

  def toProcessor: StreamGraph[Processor[A @uV, B @uV]] = {
    val (spout, subscriber) = Spout.withSubscriber[A]
    spout.via(this).to(Drain.toPublisher()).mapResult(new SubPubProcessor(subscriber, _))
  }

  def named(name: String): this.type = named(Module.ID(name))

  def named(moduleID: Module.ID): this.type = {
    moduleID
      .addBoundary(Module.Boundary.InnerEntry(firstStage.stageImpl))
      .addBoundary(Module.Boundary.InnerExit(lastStage.stageImpl))
    this
  }
}

object Pipe {

  def apply[T]: Pipe[T, T] = {
    val stage = new NopStage
    new Pipe(stage, stage)
  }

  def fromDrainAndSpout[A, B](drain: Drain[A, Unit], spout: Spout[B]): Pipe[A, B] =
    new Pipe(drain.outport, spout.inport) named "Pipe.fromDrainAndSpout"

  def fromProcessor[A, B](processor: Processor[A, B]): Pipe[A, B] =
    fromDrainAndSpout(Drain.fromSubscriber(processor), Spout.fromPublisher(processor))

  def lazyStart[A, B](onStart: () ⇒ Pipe[A, B]): Pipe[A, B] = {
    val innerPipeRef = new AtomicReference[Pipe[A, B]]
    val placeholder  = Pipe[A].asInstanceOf[Pipe[A, B]]
    @tailrec def innerPipe: Pipe[A, B] =
      innerPipeRef.get match {
        case null ⇒
          if (innerPipeRef.compareAndSet(null, placeholder)) {
            val pipe: Pipe[A, B] =
              try onStart()
              catch { case NonFatal(e) ⇒ fromDrainAndSpout(Drain.cancelling, Spout.failing(e)) }
            innerPipeRef.set(pipe)
            pipe
          } else innerPipe
        case `placeholder` ⇒
          // Thread.onSpinWait() // TODO: enable once we are on JDK9
          innerPipe
        case x ⇒ x
      }
    fromDrainAndSpout(
      drain = Drain.lazyStart(() ⇒ innerPipe.inputAsDrain).dropResult, // TODO: remove superfluous intermediate allocations
      spout = Spout.lazyStart(() ⇒ innerPipe.outputAsSpout))
  }
}
