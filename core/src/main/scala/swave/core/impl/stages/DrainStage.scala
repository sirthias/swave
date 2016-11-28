/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import scala.annotation.compileTimeOnly
import swave.core.{IllegalAsyncBoundaryException, Stage}
import swave.core.impl.{Inport, RunContext, StreamRunner}
import swave.core.impl.stages.inout.AsyncBoundaryStage
import swave.core.impl.GraphTraverser

// format: OFF
private[swave] abstract class DrainStage extends StageImpl {

  def kind: Stage.Kind.Drain

  private[this] var _assignment: StreamRunner.Assignment = _
  protected var _inputStages: List[Stage] = Nil

  override def inputStages: List[Stage] = _inputStages
  final def outputStages: List[Stage] = Nil

  final def assignDispatcherId(dispatcherId: String): Unit =
    _assignment match {
      case (null | StreamRunner.Assignment.Default(_)) =>
        _assignment = StreamRunner.Assignment(this, dispatcherId)
      case x => throw new IllegalAsyncBoundaryException("Conflicting dispatcher assignment to drain " +
        s"'${getClass.getSimpleName}': [${x.dispatcherId}] vs. [$dispatcherId]")
    }

  protected final def registerForRunnerAssignmentIfRequired(ctx: RunContext): Unit =
    if (_assignment ne null) ctx.registerRunnerAssignment(_assignment)

  @compileTimeOnly("Unresolved `connectInAndSealWith` call")
  protected final def connectInAndSealWith(f: (RunContext, Inport) ⇒ State): Unit = ()
}

private[swave] object DrainStage {

  def assignToAllPrimaryDrains(stage: Stage, dispatcherId: String): Unit =
    GraphTraverser.process(stage) {
      new GraphTraverser.Context {
        var visited = Set.empty[Stage]
        override def apply(stage: Stage): Boolean =
          !visited.contains(stage) && {
            visited += stage
            stage match {
              case x: DrainStage ⇒ x.assignDispatcherId(dispatcherId)

              case _: AsyncBoundaryStage ⇒
                throw new IllegalAsyncBoundaryException(
                  s"Cannot assign dispatcher '$dispatcherId' to drain '$stage'. " +
                    "The drain's graph contains at least one explicit async boundary.")

              case _ =>
            }
            true
          }
      }
    }
}