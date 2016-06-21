/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.macros

private[macros] trait ConnectInAndSealWith { this: Util =>
  val c: scala.reflect.macros.whitebox.Context
  import c.universe._

  def connectInAndSealWith(f: Tree): List[Tree] = unblock {
    val q"($ctx0: $_, $in0: $_) => $block0" = f
    val ctx = freshName("ctx")
    val in = freshName("in")
    val block = replaceIdents(block0, ctx0 -> ctx, in0 -> in)

    q"""
      initialState(awaitingOnSubscribe())

      def awaitingOnSubscribe() = state(
        intercept = false,

        onSubscribe = from ⇒ {
          _inputPipeElem = from.pipeElem
          ready(from)
        })

      def ready(in: Inport) = state(
        xSeal = c ⇒ {
          configureFrom(c)
          in.xSeal(c)
          val $ctx = c
          val $in = in
          $block
        })
     """
  }
}
