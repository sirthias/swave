/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.macros

private[macros] trait ConnectFanOutAndSealWith { this: Util =>
  val c: scala.reflect.macros.whitebox.Context
  import c.universe._

  def connectFanOutAndSealWith(f: Tree): List[Tree] = unblock {
    val q"($ctx0: $_, $in0: $_, $outs0: $_) => $block0" = f
    val ctx                                             = freshName("ctx")
    val in                                              = freshName("in")
    val outs                                            = freshName("outs")
    val block                                           = replaceIdents(block0, ctx0 -> ctx, in0 -> in, outs0 -> outs)

    q"""
      initialState(connecting(null, null))

      def connecting(in: Inport, outs: OutportCtx): State = state(
        onSubscribe = from ⇒ {
          if (in eq null) {
            _inputStages = from.stage :: Nil
            connecting(from, outs)
          } else throw illegalState("Double onSubscribe(" + from + ')')
        },

        subscribe = from ⇒ {
          @tailrec def rec(out: Outport, current: OutportCtx): State =
            if (current.nonEmpty) {
              if (current.out ne out) rec(out, current.tail)
              else throw illegalState("Double subscribe(" + out + ')')
            } else {
              val newOuts = createOutportCtx(out, outs)
              _outputStages = out.stage :: _outputStages
              out.onSubscribe()
              connecting(in, newOuts)
            }
          rec(from, outs)
        },

        xSeal = c ⇒ {
          if (in ne null) {
            if (outs.nonEmpty) {
              configureFrom(c)
              _outputStages = _outputStages.reverse
              in.xSeal(c)
              @tailrec def rec(current: OutportCtx): Unit =
                if (current ne null) { current.out.xSeal(c); rec(current.tail) }
              rec(outs)
              val $ctx = c
              val $in = in
              val $outs = outs
              $block
            } else throw illegalState("Unexpected xSeal(...) (unconnected downstream)")
          } else throw illegalState("Unexpected xSeal(...) (unconnected upstream)")
        })
     """
  }
}
