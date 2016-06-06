package swave.core.macros

import scala.reflect.macros.blackbox

abstract class HelperMacros {

  def requireArg(cond: Boolean): Unit = macro HelperMacros.requireArgImpl

  def requireArg(cond: Boolean, msg: Any): Unit = macro HelperMacros.requireArg1Impl

  def requireState(cond: Boolean): Unit = macro HelperMacros.requireStateImpl

  def requireState(cond: Boolean, msg: Any): Unit = macro HelperMacros.requireState1Impl
}

object HelperMacros {

  def requireArgImpl(c: blackbox.Context)(cond: c.Expr[Boolean]): c.Expr[Unit] = {
    import c.universe._
    reify {
      if (!cond.splice) throw new IllegalArgumentException
    }
  }

  def requireArg1Impl(c: blackbox.Context)(cond: c.Expr[Boolean], msg: c.Expr[Any]): c.Expr[Unit] = {
    import c.universe._
    reify {
      if (!cond.splice) {
        throw new IllegalArgumentException(msg.splice.toString)
      }
    }
  }

  def requireStateImpl(c: blackbox.Context)(cond: c.Expr[Boolean]): c.Expr[Unit] = {
    import c.universe._
    reify {
      if (!cond.splice) {
        throw new IllegalStateException
      }
    }
  }

  def requireState1Impl(c: blackbox.Context)(cond: c.Expr[Boolean], msg: c.Expr[Any]): c.Expr[Unit] = {
    import c.universe._
    reify {
      if (!cond.splice) {
        throw new IllegalStateException(msg.splice.toString)
      }
    }
  }
}
