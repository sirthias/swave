/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.macros

private[macros] trait ConnectInAndSealWith { this: Util =>
  val c: scala.reflect.macros.whitebox.Context
  import c.universe._

  def connectInAndSealWith(f: Tree): List[Tree] = unblock {
    val q"($in0: $_) => $block0" = f
    val in                       = freshName("in")
    val block                    = replaceIdents(block0, in0 -> in)

    q"""
      initialState(awaitingOnSubscribe())

      def awaitingOnSubscribe() = state(
        intercept = false,

        onSubscribe = from ⇒ {
          _inputStages = from.stageImpl :: Nil
          ready(from)
        })

      def ready(in: Inport) = state(
        xSeal = () ⇒ {
          in.xSeal(region)
          val $in = in
          $block
        })
     """
  }
}
