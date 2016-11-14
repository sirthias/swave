/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal
import swave.core.impl.{Port, RunContext, TypeLogic}

/**
  * A [[Piping]] represents a stream graph in which the ports of all stages
  * have been properly connected and which is therefore ready to be started.
  *
  * @tparam A the result type of the `run` call
  */
final class Piping[A] private[core] (port: Port, val result: A) {

  /**
    * A [[Stage]] of the graph that can serve as a basis for exploring the graph's stage layout.
    * Often used for rendering via the [[Graph.render]] method.
    */
  def stage: Stage = port.stage

  /**
    * Turns this [[Piping]] into one with a different result by mapping over the result value.
    *
    * NOTE: The result of this call and the underlying piping share the same stages.
    * This means that only one of them can be sealed and/or run (once).
    */
  def mapResult[B](f: A ⇒ B): Piping[B] = new Piping(port, f(result))

  /**
    * Prepares this piping for starting and verifies that the ports of all stages are properly connected.
    */
  def seal()(implicit env: StreamEnv): Try[SealedPiping[A]] =
    Try {
      val ctx = new RunContext(port)
      ctx.seal()
      new SealedPiping(ctx, result)
    }

  /**
    * Starts this [[Piping]] and returns either the result of type `A` if `A` is a [[scala.concurrent.Future]],
    * or otherwise `Try[A]`.
    *
    * If the stream runs synchronously the call will not return before the stream has finished running completely.
    * In this case any returned [[scala.concurrent.Future]] will be already completed.
    * Otherwise, if the stream runs asynchronously, it will return (more or less) immediately and the stream
    * will run detached from the caller thread.
    */
  def run()(implicit env: StreamEnv, ev: TypeLogic.ToTryOrFuture[A]): ev.Out =
    seal() match {
      case Success(x) ⇒ x.run()
      case Failure(e) ⇒ ev.failure(e)
    }
}

/**
  * A [[SealedPiping]] represents a stream graph that has already been sealed and that is ready to be run.
  *
  * @tparam A the result type of the `run` call
  */
final class SealedPiping[A] private[core] (ctx: RunContext, val result: A) {

  /**
    * A [[Stage]] of the graph that can serve as a basis for exploring the graph's stage layout.
    * Often used for rendering via the [[Graph.render]] method.
    */
  def stage: Stage = ctx.port.stage

  /**
    * Turns this [[SealedPiping]] into one with a different result by mapping over the result value.
    *
    * NOTE: The result of this call and the underlying [[SealedPiping]] share the same stages.
    * This means that only one of them can be run (once).
    */
  def mapResult[B](f: A ⇒ B): SealedPiping[B] = new SealedPiping(ctx, f(result))

  /**
    * Starts this [[SealedPiping]] and returns either the result of type `A` if `A` is a [[scala.concurrent.Future]],
    * or otherwise `Try[A]`.
    *
    * If the stream runs synchronously the call will not return before the stream has finished running completely.
    * In this case any returned [[scala.concurrent.Future]] will be already completed.
    * Otherwise, if the stream runs asynchronously, it will return (more or less) immediately and the stream
    * will run detached from the caller thread.
    */
  def run()(implicit ev: TypeLogic.ToTryOrFuture[A]): ev.Out =
    try {
      ctx.start()
      ev.success(result)
    } catch {
      case NonFatal(e) ⇒ ev.failure(e)
    }
}
