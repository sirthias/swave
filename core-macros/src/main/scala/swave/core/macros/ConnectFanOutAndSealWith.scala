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

      def connecting(in: Inport, outs: OutportStates): State = state(
        onSubscribe = from ⇒ {
          if (in eq null) {
            _inputPipeElem = from.pipeElem
            connecting(from, outs)
          } else illegalState("Double onSubscribe(" + from + ')')
        },

        subscribe = from ⇒ {
          @tailrec def rec(outPort: Outport, current: OutportStates): State =
            if (current.nonEmpty) {
              if (current.out ne outPort) rec(outPort, current.tail)
              else illegalState("Double subscribe(" + outPort + ')')
            } else {
              val newOuts = new OutportStates(outPort, outs, 0)
              _outputElems = newOuts
              outPort.onSubscribe()
              connecting(in, newOuts)
            }
          rec(from, outs)
        },

        xSeal = c ⇒ {
          if (in ne null) {
            if (outs.nonEmpty) {
              configureFrom(c)
              in.xSeal(c)
              @tailrec def rec(current: OutportStates): Unit =
                if (current ne null) { current.out.xSeal(c); rec(current.tail) }
              rec(outs)
              val $ctx = c
              val $in = in
              val $outs = outs
              $block
            } else illegalState("Unexpected xSeal(...) (unconnected downstream)")
          } else illegalState("Unexpected xSeal(...) (unconnected upstream)")
        })
     """
  }
}
