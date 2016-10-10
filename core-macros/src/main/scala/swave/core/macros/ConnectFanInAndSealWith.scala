/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.macros

private[macros] trait ConnectFanInAndSealWith { this: Util =>
  val c: scala.reflect.macros.whitebox.Context
  import c.universe._

  def connectFanInAndSealWith(subs0: Tree, f: Tree): List[Tree] = unblock {
    val q"($ctx0: $_, $out0: $_) => $block0" = f
    val ctx                                  = freshName("ctx")
    val out                                  = freshName("out")
    val subs                                 = freshName("subs")
    val block                                = replaceIdents(block0, ctx0 -> ctx, out0 -> out)

    q"""
      val $subs = $subs0
      _inputElems = $subs
      initialState(connecting(null, $subs))
      $subs.foreach(_.in.subscribe()) // TODO: avoid function allocation

      def connecting(out: Outport, pendingSubs: InportList): State = state(
        onSubscribe = from ⇒ {
          val newPending = pendingSubs.remove_!(from)
          if ((out ne null) && newPending.isEmpty) ready(out)
          else connecting(out, newPending)
        },

        subscribe = from ⇒ {
          if (out eq null) {
            _outputPipeElem = from.pipeElem
            from.onSubscribe()
            if (pendingSubs.isEmpty) ready(from)
            else connecting(from, pendingSubs)
          } else illegalState("Double subscribe(" + from + ')')
        })

      def ready(out: Outport) = state(
        intercept = false,

        xSeal = c ⇒ {
          configureFrom(c)
          out.xSeal(c)
          $subs.foreach(_.in.xSeal(c)) // TODO: avoid function allocation
          val $ctx = c
          val $out = out
          $block
        })
     """
  }
}
