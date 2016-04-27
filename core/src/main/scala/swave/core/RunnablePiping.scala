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

import scala.util.control.NonFatal
import swave.core.impl.{ Port, TypeLogic, StartContext }

/**
 * A [[RunnablePiping]] represents a system or network of connected pipes in which all inlet and outlets
 * have been properly connected and which is therefore ready to be started.
 */
final class RunnablePiping[T](port: Port, val result: T) {

  def pipeElem: PipeElem.Basic = port.pipeElem

  def start()(implicit env: StreamEnv): Option[Throwable] =
    try {
      StartContext.start(port, env)
      None
    } catch {
      case NonFatal(e) ⇒ Some(e)
    }

  def run()(implicit env: StreamEnv, ev: TypeLogic.TryFlatten[T]): ev.Out =
    start() match {
      case None        ⇒ ev.success(result)
      case Some(error) ⇒ ev.failure(error)
    }
}
