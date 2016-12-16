/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.macros

private[macros] trait ConnectInOutAndSealWith { this: Util =>
  val c: scala.reflect.macros.whitebox.Context
  import c.universe._

  def connectInOutAndSealWith(f: Tree, autoPropagate: Boolean): List[Tree] = unblock {
    val q"($in0: $_, $out0: $_) => $block0" = f
    val in                                  = freshName("in")
    val out                                 = freshName("out")
    val block                               = replaceIdents(block0, in0 -> in, out0 -> out)

    val autoPropagation = if (autoPropagate) { q"in.xSeal(region); out.xSeal(region)" } else q""

    q"""
      initialState(awaitingSubscribeOrOnSubscribe())

      def awaitingSubscribeOrOnSubscribe() = state(
        intercept = false,

        onSubscribe = from ⇒ {
          _inputStages = from.stageImpl :: Nil
          awaitingSubscribe(from)
        },

        subscribe = from ⇒ {
          _outputStages = from.stageImpl :: Nil
          from.onSubscribe()
          awaitingOnSubscribe(from)
        })

      def awaitingSubscribe(in: Inport) = state(
        intercept = false,

        subscribe = from ⇒ {
          _outputStages = from.stageImpl :: Nil
          from.onSubscribe()
          ready(in, from)
        })

      def awaitingOnSubscribe(out: Outport) = state(
        intercept = false,

        onSubscribe = from ⇒ {
          _inputStages = from.stageImpl :: Nil
          ready(from, out)
        })

      def ready(in: Inport, out: Outport) = state(
        intercept = false,

        xSeal = () ⇒ {
          $autoPropagation
          val $in = in
          val $out = out
          $block
        })
     """
  }
}
