/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.macros

private[macros] trait ConnectFanInAndSealWith { this: Util =>
  val c: scala.reflect.macros.whitebox.Context
  import c.universe._

  def connectFanInAndSealWith(f: Tree): List[Tree] = unblock {
    val q"($out0: $_) => $block0" = f
    val out                       = freshName("out")
    val block                     = replaceIdents(block0, out0 -> out)

    q"""
      initialState(connecting(subs))
      subs.foreach(_.in.subscribe()) // TODO: avoid function allocation

      private var $out: Outport = _

      def connecting(pendingSubs: InportList): State = state(
        intercept = false,

        onSubscribe = from ⇒ connecting(pendingSubs.remove_!(from)),

        subscribe = from ⇒ {
          if ($out eq null) {
            $out = from
            _outputStages = from.stageImpl :: Nil
            from.onSubscribe()
            stay()
          } else throw illegalState("Double subscribe(" + from + ')')
        },

        xSeal = () ⇒ {
          if ($out ne null) {
            if (pendingSubs.isEmpty) {
              $out.xSeal(region)
              subs.foreach(_.in.xSeal(region)) // TODO: avoid function allocation
              $block
            } else throw illegalState("Unexpected xSeal(...) (unconnected upstream)")
          } else throw illegalState("Unexpected xSeal(...) (unconnected downstream)")
        })
     """
  }
}
