/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.macros

private[macros] trait Util {
  val c: scala.reflect.macros.whitebox.Context
  import c.universe._

  private val ExpandBlockMarker = Literal(Constant(1234567654321L))

  def unblock(tree: Tree): List[Tree] =
    tree match {
      case Block(stats, expr) ⇒ stats :+ expr
      case x                  ⇒ x :: Nil
    }

  def unblock(stats: List[Tree], expr: Tree): Tree = if (stats.isEmpty) expr else Block(stats, expr)

  // groups the given trees into a single tree that will be re-expanded
  // by `transformTree` without introducing an extra Block level
  def exblock(trees: List[Tree]): Tree = Block(trees, ExpandBlockMarker)

  def transformTree(tree: Tree)(pf: PartialFunction[Tree, Tree]): Tree = {
    val t = new Transformer with (Tree ⇒ Tree) {
      def apply(tree: Tree) = super.transform(tree)
      override def transform(tree: Tree) = pf.applyOrElse(tree, this)
      private val fm: Tree ⇒ List[Tree] = {
        case Block(stats, ExpandBlockMarker) ⇒ stats
        case x                               ⇒ x :: Nil
      }
      override def transformTemplate(tree: Template): Template = {
        val Template(parents, self, body) = super.transformTemplate(tree)
        Template(parents, self, body flatMap fm)
      }
    }
    t.transform(tree)
  }

  def replaceIdents(tree: Tree, replacements: (TermName, TermName)*): Tree = {
    val t = new Transformer {
      override def transform(tree: Tree): Tree =
        tree match {
          case Ident(x) ⇒ replacements.indexWhere(_._1 == x) match {
            case -1 ⇒ super.transform(tree)
            case ix ⇒ Ident(replacements(ix)._2)
          }
          case _ ⇒ super.transform(tree)
        }
    }
    t.transform(tree)
  }

  def markPrivate(dd: Tree): Tree = {
    val DefDef(mods, n, td, vp, tpt, rhs) = dd
    DefDef(Modifiers(mods.flags | Flag.PRIVATE, typeNames.EMPTY, mods.annotations), n, td, vp, tpt, rhs)
  }

  def freshName(prefix: String) = TermName(c.freshName(prefix))
}
