/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import scala.reflect.macros.blackbox

package object macros {

  def requireArg(cond: Boolean): Unit = macro Macros.requireArgImpl

  def requireArg(cond: Boolean, msg: Any): Unit = macro Macros.requireArg1Impl

  def requireState(cond: Boolean): Unit = macro Macros.requireStateImpl

  def requireState(cond: Boolean, msg: Any): Unit = macro Macros.requireState1Impl
}

package macros {
  private[macros] object Macros {

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
}
