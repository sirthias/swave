/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl

import scala.annotation.tailrec
import swave.core.impl.stages.inout.NopStage
import swave.core.impl.util.InportList
import swave.core.macros._
import swave.core.{Module, Piping}
import Module.TypeLogic._
import Module._

private[core] final class ModuleImpl[I <: Module.Input, O <: Module.Output] private (val id: Module.ID,
                                                                                     val lit: Int,
                                                                                     val lot: Int,
                                                                                     construct: InportList ⇒ Any)
    extends Module[I, O] { self ⇒
  import ModuleImpl._

  /**
    * @param inports the streams of IT ::: IB
    * @return the streams of OT ::: OB as an InportList or the result instance (in case Module.Output.Result[_])
    */
  def apply(inports: InportList): Any = {
    requireState(id.boundaries.isEmpty, "Illegal module reuse")
    inports.foreach { ins ⇒
      id.addBoundary(Boundary.OuterEntry(ins.in.stage))
      ()
    }
    val outs = construct(inports)
    outs match {
      case list: InportList ⇒
        list.flatMap { ins ⇒
          val exit =
            if (inports contains ins.in) {
              // we cannot pass a stage that we marked as outer entry through and mark it as inner exit
              val nop = new NopStage
              ins.in.subscribe()(nop)
              nop
            } else ins.in
          id.addBoundary(Boundary.InnerExit(exit.stage))
          InportList(exit)
        }
      case result ⇒ result // output is `Module.Output.None` or `Module.Output.Result[_]`, i.e. nothing to do here
    }
  }

  //       IT   OT
  //   +---v----^---+
  //   | +-v----^-+ |
  //   | |  self  | |
  //   | +-v----^-+ |
  //   |   OB   IB  |
  //   |  IT2  OT2  |
  //   | +-v----^-+ |
  //   | | other  | |
  //   | +-v----^-+ |
  //   +---v----^---+
  //      OB2  IB2
  def atop[I2 <: Input, O2 <: Output](other: Module[I2, O2])(implicit ev: Atop[I, O, I2, O2]): Module[ev.IR, ev.OR] =
    ModuleImpl(lit, lot) { `it+ib2` ⇒
      val otherImpl = ModuleImpl(other)
      var it        = `it+ib2`
      val ib2       = if (lit == 0) { it = InportList.empty; `it+ib2` } else `it+ib2`.drop(lit)
      val ib        = nops(otherImpl.lot)
      val `ot+ob`   = self(it append ib).asInstanceOf[InportList]

      var ot   = `ot+ob`
      val ob   = if (lot == 0) { ot = InportList.empty; `ot+ob` } else `ot+ob`.drop(lot)
      val outs = otherImpl(ob append ib2)

      outs match {
        case x: InportList ⇒
          val `ot2+ob2` = x
          var ot2       = `ot2+ob2`
          val ob2       = if (otherImpl.lot == 0) { ot2 = InportList.empty; `ot2+ob2` } else `ot2+ob2`.drop(otherImpl.lot)
          connect(ib, ot2)
          ot append ob2
        case result ⇒ result
      }
    }

  /**
    * Returns a new [[Module]] which performs the same logic as this one but with all forward/backward directions
    * flipped. The new [[Module]] carries the same name as this instance.
    */
  def flip(implicit ev: Flip[I, O]): Module[ev.IR, ev.OR] =
    ModuleImpl(lit, lot) { `it+ib` ⇒
      var it = `it+ib`
      val ib = if (lit == 0) { it = InportList.empty; `it+ib` } else `it+ib`.drop(lit)
      self(ib append it) match {
        case outs: InportList ⇒
          val `ot+ob` = outs
          var ot      = `ot+ob`
          val ob      = if (lot == 0) { ot = InportList.empty; `ot+ob` } else `ot+ob`.drop(lot)
          ob append ot
        case result ⇒ result
      }
    }

  //   +......+    +......+
  //   .      .    .      .
  //   .     IT    OT     .
  //   .  +---v----^---+  .
  //   .  | +-v----^-+ |  .
  //   .  | |  self  | |  .
  //   .  | +-v----^-+ |  .
  //   .  |   OB   IB  |  .
  //   .  |            |  .
  //   .  |  IT2  OT2  |  .
  //   .  | +-v----^-+ |  .
  //   .  | | other  | |  .
  //   .  | +-v----^-+ |  .
  //   .  +---v----^---+  .
  //   .     OB2  IB2     .
  //   .      .    .      .
  //   +......+    +......+
  def crossJoin[I2 <: Input, O2 <: Output](other: Module[I2, O2])(implicit ev: CrossJoin[I, O, I2, O2]): Piping[Unit] = {
    val otherImpl = ModuleImpl(other)
    requireArg(
      lit + lot + otherImpl.lit + otherImpl.lot > 0,
      "Cannot cross-join two modules without inputs and outputs")
    val `it+ib` = nops(lit + otherImpl.lot)
    val `ot+ob` = self(`it+ib`).asInstanceOf[InportList]
    var ot      = `ot+ob`
    val ob      = if (lot == 0) { ot = InportList.empty; `ot+ob` } else `ot+ob`.drop(lot)

    val `ot2+ob2` = otherImpl(ob append ot).asInstanceOf[InportList]
    var ot2       = `ot2+ob2`
    val ob2       = if (otherImpl.lot == 0) { ot2 = InportList.empty; `ot2+ob2` } else `ot2+ob2`.drop(otherImpl.lot)
    connect(`it+ib`, ob2 append ot2)

    val entryElem = if (`it+ib`.nonEmpty) `it+ib`.in else `ot+ob`.in
    new Piping(entryElem, ())
  }

  /**
    * Returns a copy of this [[Module]] with the name changed to the given one.
    */
  def named(name: String): Module[I, O] = new ModuleImpl[I, O](ID(name), lit, lot, construct)
}

private[core] object ModuleImpl {

  def apply[I <: Input, O <: Output](lit: Int, lot: Int)(construct: InportList ⇒ Any): Module[I, O] =
    new ModuleImpl[I, O](ID(), lit, lot, construct)

  def apply(module: Module[_, _]) = module.asInstanceOf[ModuleImpl[_, _]]

  @tailrec private def nops(count: Int, result: InportList = InportList.empty): InportList =
    if (count > 0) nops(count - 1, new NopStage +: result) else result

  @tailrec private def connect(nops: InportList, outs: InportList): Unit =
    if (nops.nonEmpty) {
      outs.in.subscribe()(nops.in.asInstanceOf[NopStage])
      connect(nops.tail, outs.tail)
    }
}
