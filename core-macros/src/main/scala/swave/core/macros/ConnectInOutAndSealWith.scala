/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.macros

private[macros] trait ConnectInOutAndSealWith { this: Util =>
  val c: scala.reflect.macros.whitebox.Context
  import c.universe._

  def connectInOutAndSealWith(f: Tree): List[Tree] = unblock {
    val q"($in0: $_, $out0: $_) => $block0" = f
    val in                                  = freshName("in")
    val out                                 = freshName("out")
    val block                               = replaceIdents(block0, in0 -> in, out0 -> out)

    q"""
      initialState(connecting(null, null))

      def connecting(in: Inport, out: Outport) = state(
        intercept = false,

        onSubscribe = from ⇒ {
          if (in eq null) {
            _inputStages = from.stageImpl :: Nil
            connecting(from, out)
          } else failAlreadyConnected("Upstream", from)
        },

        subscribe = from ⇒ {
          if (out eq null) {
            _outputStages = from.stageImpl :: Nil
            from.onSubscribe()
            connecting(in, from)
          } else failAlreadyConnected("Downstream", from)
        },

        xSeal = () ⇒ {
          if (in ne null) {
            if (out ne null) {
              in.xSeal(region)
              out.xSeal(region)
              var $in = in
              var $out = out
              $block
            } else failUnclosedStreamGraph("downstream")
          } else failUnclosedStreamGraph("upstream")
        })
     """
  }
}
