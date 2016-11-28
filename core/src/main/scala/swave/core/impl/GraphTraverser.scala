/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import scala.annotation.tailrec
import swave.core.Stage

private[impl] object GraphTraverser {

  // mutable context keeping the folding state
  abstract class Context {
    // processes the stage and returns true if folding should continue "behind" the stage
    def apply(stage: Stage): Boolean
  }

  @tailrec def process(stage: Stage, from: Stage = null)(implicit ctx: Context): Unit =
    if ((stage ne from) && ctx(stage)) {
      val ins  = stage.inputStages
      val outs = stage.outputStages
      if (ins eq Nil) {
        if (outs eq Nil) {
          /* 0:0 */
          throw new IllegalStateException // no inputs and no outputs?
        } else if (outs.tail eq Nil) {
          /* 0:1 */
          process(outs.head, stage)
        } else {
          /* 0:x */
          processAll(outs, stage)
        }
      } else if (ins.tail eq Nil) {
        if (outs eq Nil) {
          /* 1:0 */
          process(ins.head, stage)
        } else if (outs.tail eq Nil) {
          /* 1:1 */
          if (from eq outs.head) process(ins.head, stage)
          else if (from eq ins.head) process(outs.head, stage)
          else {
            _process(ins.head, stage)
            process(outs.head, stage)
          }
        } else {
          /* 1:x */
          _process(ins.head, stage)
          processAll(outs, stage)
        }
      } else {
        if (outs eq Nil) {
          /* x:0 */
          processAll(ins, stage)
        } else if (outs.tail eq Nil) {
          /* x:1 */
          processAll(ins, stage)
          process(outs.head, stage)
        } else {
          /* x:x */
          processAll(ins, stage)
          processAll(outs, stage)
        }
      }
    }

  // simple forwarder to circumvent "not in tail position" compiler error
  private def _process(s: Stage, from: Stage)(implicit ctx: Context): Unit = process(s)

  @tailrec private def processAll(list: List[Stage], from: Stage)(implicit ctx: Context): Unit =
    if (list ne Nil) {
      process(list.head)
      processAll(list.tail, from)
    }

}
