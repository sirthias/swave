package swave.core.macros

trait ConnectInOutAndSealWith { this: Util =>
  val c: scala.reflect.macros.whitebox.Context
  import c.universe._

  def connectInOutAndSealWith(f: Tree): List[Tree] = unblock {
    val q"($ctx0: $_, $in0: $_, $out0: $_) => $block0" = f
    val ctx = freshName("ctx")
    val in = freshName("in")
    val out = freshName("out")
    val block = replaceIdents(block0, ctx0 -> ctx, in0 -> in, out0 -> out)

    q"""
      initialState(awaitingSubscribeOrOnSubscribe())

      def awaitingSubscribeOrOnSubscribe() = state(
        intercept = false,

        onSubscribe = from ⇒ {
          _inputPipeElem = from.pipeElem
          awaitingSubscribe(from)
        },

        subscribe = from ⇒ {
          _outputPipeElem = from.pipeElem
          from.onSubscribe()
          awaitingOnSubscribe(from)
        })

      def awaitingSubscribe(in: Inport) = state(
        intercept = false,

        subscribe = from ⇒ {
          _outputPipeElem = from.pipeElem
          from.onSubscribe()
          ready(in, from)
        })

      def awaitingOnSubscribe(out: Outport) = state(
        intercept = false,

        onSubscribe = from ⇒ {
          _inputPipeElem = from.pipeElem
          ready(from, out)
        })

      def ready(in: Inport, out: Outport) = state(
        intercept = false,

        xSeal = c ⇒ {
          configureFrom(c.env)
          in.xSeal(c)
          out.xSeal(c)
          val $ctx = c
          val $in = in
          val $out = out
          $block
        })
     """
  }
}
