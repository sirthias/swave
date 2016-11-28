/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.macros

private[macros] trait ConnectOutAndSealWith { this: Util =>
  val c: scala.reflect.macros.whitebox.Context
  import c.universe._

  def connectOutAndSealWith(f: Tree): List[Tree] = unblock {
    val q"($ctx0: $_, $out0: $_) => $block0" = f
    val ctx                                  = freshName("ctx")
    val out                                  = freshName("out")
    val block                                = replaceIdents(block0, ctx0 -> ctx, out0 -> out)

    q"""
      initialState(awaitingSubscribe())

      def awaitingSubscribe() = state(
        intercept = false,

        subscribe = from ⇒ {
          _outputStages = from.stageImpl :: Nil
          from.onSubscribe()
          ready(from)
        })

      def ready(out: Outport) = state(
        intercept = false,

        xSeal = c ⇒ {
          configureFrom(c)
          out.xSeal(c)
          val $ctx = c
          val $out = out
          $block
        })
     """
  }
}
