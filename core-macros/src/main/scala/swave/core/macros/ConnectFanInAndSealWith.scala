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
      initialState(connecting(null, subs))
      subs.foreach(_.in.subscribe()) // TODO: avoid function allocation

      def connecting(out: Outport, pendingSubs: InportList): State = state(
        onSubscribe = from ⇒ {
          val newPending = pendingSubs.remove_!(from)
          if ((out ne null) && newPending.isEmpty) ready(out)
          else connecting(out, newPending)
        },

        subscribe = from ⇒ {
          if (out eq null) {
            _outputStages = from.stageImpl :: Nil
            from.onSubscribe()
            if (pendingSubs.isEmpty) ready(from)
            else connecting(from, pendingSubs)
          } else throw illegalState("Double subscribe(" + from + ')')
        })

      def ready(out: Outport) = state(
        intercept = false,

        xSeal = () ⇒ {
          out.xSeal(region)
          subs.foreach(_.in.xSeal(region)) // TODO: avoid function allocation
          val $out = out
          $block
        })
     """
  }
}
