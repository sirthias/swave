/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import scala.annotation.tailrec
import swave.core.Stage

private[impl] object GraphFolder {

  // mutable context keeping the folding state
  abstract class FoldContext {
    // processes the stage and returns true if folding should continue "behind" the stage
    def apply(stage: Stage): Boolean
  }

  @tailrec def fold(stage: Stage, from: Stage = null)(implicit ctx: FoldContext): Unit =
    if ((stage ne from) && ctx(stage)) {
      val ins  = stage.inputStages
      val outs = stage.outputStages
      if (ins.isEmpty) {
        if (outs.isEmpty) {
          /* 0:0 */
          throw new IllegalStateException // no inputs and no outputs?
        } else if (outs.tail.isEmpty) {
          /* 0:1 */
          fold(outs.head, stage)
        } else {
          /* 0:x */
          foldAll(outs, stage)
        }
      } else if (ins.tail.isEmpty) {
        if (outs.isEmpty) {
          /* 1:0 */
          fold(ins.head, stage)
        } else if (outs.tail.isEmpty) {
          /* 1:1 */
          if (from eq outs.head) fold(ins.head, stage)
          else if (from eq ins.head) fold(outs.head, stage)
          else {
            _fold(ins.head, stage)
            fold(outs.head, stage)
          }
        } else {
          /* 1:x */
          _fold(ins.head, stage)
          foldAll(outs, stage)
        }
      } else {
        if (outs.isEmpty) {
          /* x:0 */
          foldAll(ins, stage)
        } else if (outs.tail.isEmpty) {
          /* x:1 */
          foldAll(ins, stage)
          fold(outs.head, stage)
        } else {
          /* x:x */
          foldAll(ins, stage)
          foldAll(outs, stage)
        }
      }
    }

  // simple forwarder to circumvent "not in tail position" compiler error
  private def _fold(s: Stage, from: Stage)(implicit ctx: FoldContext): Unit = fold(s)

  @tailrec private def foldAll(list: List[Stage], from: Stage)(implicit ctx: FoldContext): Unit =
    if (list ne Nil) {
      fold(list.head)
      foldAll(list.tail, from)
    }

}
