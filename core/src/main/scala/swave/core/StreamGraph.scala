/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal
import scala.concurrent.Future
import swave.core.impl.stages.StageImpl
import swave.core.impl.{RunContext, TypeLogic}

/**
  * A [[StreamGraph]] represents a stream graph in which the ports of all stages
  * have been properly connected and which is therefore ready to be started.
  *
  * If the type parameter `A` is a [[Future]] then `run().result` returns an `A`, otherwise a `Try[A]`.
  */
final class StreamGraph[+A] private[core] (stageImpl: StageImpl, val result: A) {

  /**
    * A [[Stage]] of the graph that can serve as a basis for exploring the graph's stage layout.
    * Often used for rendering via the [[Graph.render]] method.
    */
  def stage: Stage = stageImpl

  /**
    * Turns this [[StreamGraph]] into one with a different result by mapping over the result value.
    *
    * NOTE: The result of this call and the underlying [[StreamGraph]] share the same stages.
    * This means that only one of them can be sealed and/or run (once).
    */
  def mapResult[B](f: A ⇒ B): StreamGraph[B] = new StreamGraph(stageImpl, f(result))

  /**
    * Prepares this [[StreamGraph]] for starting and verifies that the ports of all stages are properly connected.
    */
  def seal()(implicit env: StreamEnv): Try[SealedStreamGraph[A]] =
    seal(new RunContext(stageImpl))

  /**
    * Seals and starts this [[StreamGraph]] and returns the [[StreamRun]] instance for the run.
    * The `result` of the returned [[StreamRun]] has either type `A` if `A` is a [[Future]], or otherwise `Try[A]`.
    *
    * If the stream runs synchronously the call will not return before the stream has finished running completely.
    * In this case the `result` of the returned [[StreamRun]] will be already completed if it's a [[Future]].
    *
    * Otherwise, if the stream runs asynchronously, it will return (more or less) immediately and the stream
    * will run detached from the caller thread.
    */
  def run()(implicit env: StreamEnv, ev: TypeLogic.ToTryOrFuture[A]): StreamRun[ev.Out] = {
    val ctx = new RunContext(stageImpl)
    seal(ctx) match {
      case Success(x) ⇒ x.run()
      case Failure(e) ⇒ new StreamRun(ctx, ev.failure(e))
    }
  }

  private def seal(ctx: RunContext)(implicit env: StreamEnv): Try[SealedStreamGraph[A]] =
    try {
      ctx.seal()
      Success(new SealedStreamGraph(ctx, result))
    } catch {
      case NonFatal(e) => Failure(e)
    }
}

/**
  * A [[SealedStreamGraph]] represents a stream graph that has already been sealed and that is ready to be run.
  *
  * If the type parameter `A` is a [[Future]] then `run().result` returns an `A`, otherwise a `Try[A]`.
  */
final class SealedStreamGraph[+A] private[core] (ctx: RunContext, val result: A) {

  /**
    * A [[Stage]] of the graph that can serve as a basis for exploring the graph's stage layout.
    * Often used for rendering via the [[Graph.render]] method.
    */
  def stage: Stage = ctx.stage

  /**
    * Turns this [[SealedStreamGraph]] into one with a different result by mapping over the result value.
    *
    * NOTE: The result of this call and the underlying [[SealedStreamGraph]] share the same stages.
    * This means that only one of them can be run (once).
    */
  def mapResult[B](f: A ⇒ B): SealedStreamGraph[B] = new SealedStreamGraph(ctx, f(result))

  /**
    * Starts this [[SealedStreamGraph]] and returns the [[StreamRun]] instance for the run.
    * The `result` of the returned [[StreamRun]] has either type `A` if `A` is a [[Future]], or otherwise `Try[A]`.
    *
    * If the stream runs synchronously the call will not return before the stream has finished running completely.
    * In this case any returned [[Future]] will be already completed.
    *
    * Otherwise, if the stream runs asynchronously, it will return (more or less) immediately and the stream
    * will run detached from the caller thread.
    */
  def run()(implicit ev: TypeLogic.ToTryOrFuture[A]): StreamRun[ev.Out] = {
    val runResult =
      try {
        ctx.start()
        ev.success(result)
      } catch {
        case NonFatal(e) ⇒ ev.failure(e)
      }
    new StreamRun(ctx, runResult)
  }
}

/**
  * A [[StreamRun]] represents a stream graph that has already been started and thus is either running or
  * has already terminated.
  *
  * @tparam A the type of the `result` member
  */
final class StreamRun[+A] private[core] (ctx: RunContext, val result: A) {

  /**
    * A [[Stage]] of the graph that can serve as a basis for exploring the graph's stage layout.
    * Often used for rendering via the [[Graph.render]] method.
    */
  def stage: Stage = ctx.stage

  /**
    * Turns this [[StreamRun]] into one with a different result by mapping over the result value.
    */
  def mapResult[B](f: A ⇒ B): StreamRun[B] = new StreamRun(ctx, f(result))

  /**
    * A [[Future]] that will be completed when all stages of the graph, in all async regions, have terminated.
    * This future can, for example, serve as a trigger to safely shut down the [[StreamEnv]].
    */
  def termination: Future[Unit] =
    ctx.global match {
      case x: RunContext.AsyncGlobal => x.termination.future
      case _                         => Future.successful(())
    }
}
