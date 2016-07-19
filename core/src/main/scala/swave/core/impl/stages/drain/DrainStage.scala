/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.drain

import scala.annotation.compileTimeOnly
import swave.core.{ IllegalAsyncBoundaryException, PipeElem }
import swave.core.impl.{ RunContext, Inport }
import swave.core.impl.stages.Stage

// format: OFF
private[swave] abstract class DrainStage extends Stage { this: PipeElem.Drain =>

  protected final var _inputPipeElem: PipeElem = PipeElem.Unconnected
  private[this] var _dispatcherId: String = null

  final def inputElem = _inputPipeElem

  protected final def setInputElem(elem: PipeElem): Unit =
    _inputPipeElem = elem

  final def assignDispatcherId(dispatcherId: String): Unit =
    if ((_dispatcherId eq null) || _dispatcherId.isEmpty) _dispatcherId = dispatcherId
    else if (dispatcherId.nonEmpty && dispatcherId != _dispatcherId)
      throw new IllegalAsyncBoundaryException("Conflicting dispatcher assignment to drain " +
        s"'${getClass.getSimpleName}': [${_dispatcherId}] vs. [$dispatcherId]")

  protected final def registerForRunnerAssignmentIfRequired(ctx: RunContext): Unit =
    if (_dispatcherId ne null) ctx.registerForRunnerAssignment(this, _dispatcherId)

  @compileTimeOnly("Unresolved `connectInAndSealWith` call")
  protected final def connectInAndSealWith(f: (RunContext, Inport) ⇒ State): Unit = ()
}
