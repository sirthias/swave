/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal
import swave.core.impl.{Port, RunContext, TypeLogic}

/**
  * A [[Piping]] represents a system or network of connected pipes in which all inlet and outlets
  * have been properly connected and which is therefore ready to be started.
  */
final class Piping[A] private[core] (port: Port, val result: A) {

  def pipeElem: PipeElem = port.pipeElem

  def mapResult[B](f: A ⇒ B): Piping[B] = new Piping(port, f(result))

  def seal()(implicit env: StreamEnv): Try[SealedPiping[A]] =
    Try {
      val ctx = new RunContext(port)
      ctx.seal()
      new SealedPiping(ctx, result)
    }

  def run()(implicit env: StreamEnv, ev: TypeLogic.ToTryOrFuture[A]): ev.Out =
    seal() match {
      case Success(x) ⇒ x.run()
      case Failure(e) ⇒ ev.failure(e)
    }
}

final class SealedPiping[A] private[core] (ctx: RunContext, val result: A) {

  def pipeElem: PipeElem = ctx.port.pipeElem

  def mapResult[B](f: A ⇒ B): SealedPiping[B] = new SealedPiping(ctx, f(result))

  def run()(implicit ev: TypeLogic.ToTryOrFuture[A]): ev.Out =
    try {
      ctx.start()
      ev.success(result)
    } catch {
      case NonFatal(e) ⇒ ev.failure(e)
    }
}
