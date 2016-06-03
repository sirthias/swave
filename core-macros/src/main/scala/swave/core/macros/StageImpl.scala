/*
 * Copyright © 2016 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swave.core.macros

import scala.annotation.{ StaticAnnotation, compileTimeOnly }

@compileTimeOnly("Unresolved @StageImpl")
final class StageImpl(dump: Boolean = false, tracing: Boolean = false) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro StageImplMacro.generateStage
}

/**
 * Macro transforming a stage definition from its dev-friendly into its execution-friendly form.
 *
 * dev-friendly form: a set of state definitions whereby each one bundles a set of signal handlers
 * execution-friendly form: set of signal handlers whereby each one holds the logic for all states
 *
 * Apart from regular class members a stage definition contains "State Defs",
 * which are methods defs satisfying these constraints:
 * - no modifiers
 * - no type parameters
 * - return type `State`
 * - only a single parameter list
 * - body consists only of zero or more nested `def`s ("Aux Defs") and exactly one `state(...)` call
 *
 * A State Def may be nested in another `def` (single level only!), the "State Parent Def",
 * if this satisfies these constraints:
 * - no modifiers
 * - return type `State`
 * - no type parameters
 * - only a single parameter list
 * - body consists only of one or more State Defs and exactly one call to a nested State Def (the "Entry Call")
 *
 *
 * Basic macro logic:
 *
 * 1. Identify all State Parent Defs and State Defs in the class.
 *    Assign each state an `int` ID increasing from 1.
 *    (Zero is the encoding for the always automatically defined STOPPED state).
 *
 * 2. Determine the Set of all State Parent Def and State Def parameters (the "Parameter Set").
 *    Params with identical names must have identical type and be semantically the same!
 *
 * 3. Add `private[this] var` members for all parameters in the Parameter Set.
 *    The `var` member for parameter `foo` gets the name `__foo`.
 *
 * 4. For each State Parent Def (the "SPD"):
 *    - in the SPD's body: replace all reading accesses to an SPD parameter
 *      with reading accesses to the respective `var` member name
 *    - move all nested State Defs up to the primary class level
 *    - outside the SPD: transform all calls to the SPD in the following way:
 *      - for every arg of the SPD call:
 *        - if the arg for parameter with name `x` is simply `Ident(TermName("x"))` then simply remove the arg
 *        - otherwise turn the arg expression into an assignment for the respective `var` member and remove the arg
 *      - replace the SPD call with the SPD's Entry Call
 *    - remove the now unused SPD definition itself
 *
 * 5. For each State Def (the "SD"):
 *    - in the SD's body:
 *      replace any reading access to an SD parameter with a reading access to the respective `var` member
 *    - move all nested Aux Defs onto the primary class level where they become `private` methods
 *    - mark the SD `private`
 *    - replace the SD's parameter list with the empty List
 *    - replace the SD's body (the `state` call) with the literal state ID
 *
 * 6. Transform all calls to any State Def (in the whole class definition) in the following way:
 *    - for every argument of the call
 *      - if the arg for parameter with name `x` is simply `Ident(TermName("x"))` then simply remove the arg
 *      - otherwise turn the arg expression into an assignment for the respective `var` member and remove the arg
 *
 * 7. Generate `interceptingStates`, `stateName` and signal handler implementations
 */

// TODO
// - improve hygiene (e.g. naming resilience)
class StageImplMacro(val c: scala.reflect.macros.whitebox.Context) extends Util
    with ConnectFanInAndSealWith
    with ConnectFanOutAndSealWith
    with ConnectInAndSealWith
    with ConnectInOutAndSealWith
    with ConnectOutAndSealWith {
  import c.universe._

  var stateHandlers = Map.empty[String, StateHandlers]
  val debugMode: Boolean = annotationFlag("dump")
  val tracing: Boolean = annotationFlag("tracing")

  private def annotationFlag(flag: String) =
    c.prefix.tree match {
      case q"new StageImpl(..$params)" ⇒
        params.collectFirst { case AssignOrNamedArg(Ident(TermName(`flag`)), Literal(Constant(true))) ⇒ }.isDefined
      case _ ⇒ false
    }

  def generateStage(annottees: c.Expr[Any]*): c.Expr[Any] =
    annottees.map(_.tree) match {
      case (stageClass: ClassDef) :: Nil                           ⇒ transformStageImpl(stageClass, None)
      case (stageClass: ClassDef) :: (companion: ModuleDef) :: Nil ⇒ transformStageImpl(stageClass, Some(companion))
      case other :: Nil ⇒ c.abort(
        c.enclosingPosition,
        "@StageImpl can only be applied to final classes inheriting from swave.core.impl.stages.Stage")
    }

  def transformStageImpl(stageClass: ClassDef, companion: Option[ModuleDef]): c.Expr[Any] = {
    if (!stageClass.mods.hasFlag(Flag.FINAL)) c.abort(stageClass.pos, "Stage implementations must be final")
    val sc1 = expandConnectingStates(c.untypecheck(stageClass))
    val stateParentDefs = sc1.impl.body collect { case StateParentDef(x) ⇒ x }
    val parameterSet = determineParameterSet(stateParentDefs, stateDefs(sc1))
    val sc2 = stateParentDefs.foldLeft(sc1: Tree)(removeStateParentDef(parameterSet))
    val sc3 = stateDefs(sc2).foldLeft(sc2)(transformStateDef(parameterSet))
    val sc4 = addMembers(sc3, varMembers(parameterSet), assignInterceptingStates :: stateNameImpl ::: signalHandlersImpl)
    val sc5 = transformStateCallSites(sc4)

    val transformedStageClass = sc5
    val result = c.Expr {
      companion match {
        case Some(comp) ⇒ q"""
          $transformedStageClass
          $comp"""
        case None ⇒ transformedStageClass
      }
    }
    if (debugMode) trace(s"\n******** Generated stage class ${stageClass.name} ********\n\n" + showCode(result.tree))
    result
  }

  def expandConnectingStates(stageClass: Tree): ClassDef = {
    val ClassDef(mods, name, typeDefs, Template(parents, self, body0)) = stageClass
    val body = body0 flatMap {
      case q"connectFanInAndSealWith($subs) { $f }" ⇒ connectFanInAndSealWith(subs, f)
      case q"connectFanOutAndSealWith { $f }"       ⇒ connectFanOutAndSealWith(f)
      case q"connectInAndSealWith { $f }"           ⇒ connectInAndSealWith(f)
      case q"connectInOutAndSealWith { $f }"        ⇒ connectInOutAndSealWith(f)
      case q"connectOutAndSealWith { $f }"          ⇒ connectOutAndSealWith(f)
      case x                                        ⇒ x :: Nil
    }
    ClassDef(mods, name, typeDefs, Template(parents, self, body))
  }

  type ParameterSet = Map[String, Tree] // parameter -> tpt (type tree)

  def determineParameterSet(stateParentDefs: List[StateParentDef], stateDefs: List[StateDef]): ParameterSet = {
    def addParamNames(set: ParameterSet, params: List[ValDef]) =
      params.foldLeft(set) { (set, p) ⇒
        val name = p.name.decodedName.toString
        set.get(name) match {
          case None ⇒ set.updated(name, p.tpt)
          case Some(tpt) ⇒
            if (p.tpt.symbol.name.toString != tpt.symbol.name.toString) {
              c.abort(p.pos, s"State parameter with name $name is already defined with another type")
            } else set
        }
      }
    def addStateDefParams(set: ParameterSet, sds: List[StateDef]) =
      sds.foldLeft(set) { (set, sd) ⇒ addParamNames(set, sd.params) }

    val res0 = stateParentDefs.foldLeft(Map.empty[String, Tree]) { (set, spd) ⇒
      addStateDefParams(addParamNames(set, spd.params), spd.defDefs.collect(scala.Function.unlift(StateDef.unapply)))
    }
    addStateDefParams(res0, stateDefs)
  }

  def stateDefs(stageClass: Tree): List[StateDef] =
    stageClass.asInstanceOf[ClassDef].impl.body collect { case StateDef(x) ⇒ x }

  def removeStateParentDef(parameterSet: ParameterSet)(stageClass: Tree, spd: StateParentDef) = {
    val StateParentDef(tree, name, params, defDefs, entryCall) = spd
    transformTree(stageClass) {
      case `tree` ⇒ exblock(defDefs.map(dd ⇒ markPrivate(transformParamAccess(parameterSet, dd))))
      case x @ Apply(Ident(TermName(`name`)), args) ⇒
        val transformedEntryCall = transformParamAccess(parameterSet, entryCall)
        unblock(stateCallVarAssignments(x.pos, params, args), transformedEntryCall)
    }
  }

  def transformStateDef(parameterSet: ParameterSet)(stageClass: Tree, sd: StateDef) = {
    val StateDef(tree, name, params, body) = sd
    transformTree(stageClass) {
      case `tree` ⇒
        val StateDefBody(auxDefs, handlers) = transformParamAccess(parameterSet, body)
        val stateId = stateHandlers.size + 1
        stateHandlers = stateHandlers.updated(name, StateHandlers(stateId, params, handlers))
        val state = q"private def ${TermName(name)}(): State = $stateId"
        exblock(state :: auxDefs.map(markPrivate))
      case x @ DefDef(_, _, _, _, _, _) ⇒ x // don't descend into other methods
    }
  }

  def transformStateCallSites(stageClass: Tree) =
    transformTree(stageClass) {
      case tree @ Apply(Ident(TermName(name)), args) if stateHandlers contains name ⇒
        unblock(stateCallVarAssignments(tree.pos, stateHandlers(name).params, args), q"${TermName(name)}()")
    }

  def addMembers(stageClass: Tree, prepend: List[ValDef], append: List[Tree]) = {
    val ClassDef(mods, name, typeDefs, Template(parents, self, body)) = stageClass
    ClassDef(mods, name, typeDefs, Template(parents, self, prepend ::: body ::: append))
  }

  def varMembers(parameterSet: ParameterSet): List[ValDef] =
    parameterSet.map { case (name, tpt) ⇒ q"private[this] var ${TermName("__" + name)}: $tpt = _" }(collection.breakOut)

  def signalHandlersImpl: List[Tree] = {

    def compact(cases: List[CaseDef]): List[CaseDef] =
      cases.foldLeft(List.empty[CaseDef]) { (acc, cd) ⇒
        acc indexWhere { case cq"${ _ } => $body" ⇒ body equalsStructure cd.body } match {
          case -1 ⇒ cd :: acc
          case ix ⇒
            val altPat = acc(ix) match {
              case cq"$head | ..$tail => ${ _ }" ⇒ pq"${cd.pat} | ..${head :: tail}"
              case cq"$pat => ${ _ }"            ⇒ pq"${cd.pat} | $pat"
            }
            acc.updated(ix, cq"$altPat => ${cd.body}")
        }
      }.reverse

    def switch(signal: String, cases: List[CaseDef]): Tree = {
      val theMatch = q"stay() match { case ..$cases }"
      if (tracing) {
        val res = freshName("res")
        q"""println(this + ${Literal(Constant(": entering " + signal))})
            val $res = $theMatch
            println(this + ${Literal(Constant(s": exiting `$signal` with new state `"))} + stateName($res) + "`")
            $res
         """
      } else theMatch
    }

    def subscribeDef = {
      val from = freshName("from")
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._subscribe0($from)" :: Nil) { (acc, sh) ⇒
          sh.subscribe.fold(acc) {
            case q"($from0) => $body0" ⇒ cq"${sh.id} => ${replaceIdents(body0, from0.name → from)}" :: acc
          }
        }
      }
      if (cases.size > 1)
        q"protected override def _subscribe0($from: swave.core.impl.Outport): State = ${switch("subscribe", cases)}"
      else q"()"
    }

    def requestDef = {
      val n = freshName("n")
      val from = freshName("from")
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._request0($n, $from)" :: Nil) { (acc, sh) ⇒
          sh.request.fold(acc) {
            case q"($n0, $from0) => $body0" ⇒ cq"${sh.id} => ${replaceIdents(body0, n0.name → n, from0.name → from)}" :: acc
          }
        }
      }
      if (cases.size > 1) {
        q"protected override def _request0($n: Int, $from: swave.core.impl.Outport): State = ${switch("request", cases)}"
      } else q"()"
    }

    def cancelDef = {
      val from = freshName("from")
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._cancel0($from)" :: Nil) { (acc, sh) ⇒
          sh.cancel.fold(acc) { tree ⇒
            val caseBody = tree match {
              case q"($from0) => $body0" ⇒ replaceIdents(body0, from0.name → from)
              case x                     ⇒ q"$x($from)"
            }
            cq"${sh.id} => $caseBody" :: acc
          }
        }
      }
      if (cases.size > 1) {
        q"protected override def _cancel0($from: swave.core.impl.Outport): State = ${switch("cancel", cases)}"
      } else q"()"
    }

    def onSubscribeDef = {
      val from = freshName("from")
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._onSubscribe0($from)" :: Nil) { (acc, sh) ⇒
          sh.onSubscribe.fold(acc) {
            case q"($from0) => $body0" ⇒ cq"${sh.id} => ${replaceIdents(body0, from0.name → from)}" :: acc
          }
        }
      }
      if (cases.size > 1) {
        q"protected override def _onSubscribe0($from: swave.core.impl.Inport): State = ${switch("onSubscribe", cases)}"
      } else q"()"
    }

    def onNextDef = {
      val elem = freshName("elem")
      val from = freshName("from")
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._onNext0($elem, $from)" :: Nil) { (acc, sh) ⇒
          sh.onNext.fold(acc) {
            case q"($elem0, $from0) => $body0" ⇒ cq"${sh.id} => ${replaceIdents(body0, elem0.name → elem, from0.name → from)}" :: acc
          }
        }
      }
      if (cases.size > 1) {
        q"protected override def _onNext0($elem: AnyRef, $from: swave.core.impl.Inport): State = ${switch("onNext", cases)}"
      } else q"()"
    }

    def onCompleteDef = {
      val from = freshName("from")
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._onComplete0($from)" :: Nil) { (acc, sh) ⇒
          sh.onComplete.fold(acc) { tree ⇒
            val caseBody = tree match {
              case q"($from0) => $body0" ⇒ replaceIdents(body0, from0.name → from)
              case x                     ⇒ q"$x($from)"
            }
            cq"${sh.id} => $caseBody" :: acc
          }
        }
      }
      if (cases.size > 1) {
        q"protected override def _onComplete0($from: swave.core.impl.Inport): State = ${switch("onComplete", cases)}"
      } else q"()"
    }

    def onErrorDef = {
      val error = freshName("error")
      val from = freshName("from")
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._onError0($error, $from)" :: Nil) { (acc, sh) ⇒
          sh.onError.fold(acc) { tree ⇒
            val caseBody = tree match {
              case q"($error0, $from0) => $body0" ⇒ replaceIdents(body0, error0.name → error, from0.name → from)
              case x                              ⇒ q"$x($error, $from)"
            }
            cq"${sh.id} => $caseBody" :: acc
          }
        }
      }
      if (cases.size > 1) {
        q"protected override def _onError0($error: Throwable, $from: swave.core.impl.Inport): State = ${switch("onError", cases)}"
      } else q"()"
    }

    def xSealDef = {
      val ctx = freshName("ctx")
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._xSeal($ctx)" :: Nil) { (acc, sh) ⇒
          sh.xSeal.fold(acc) {
            case q"($ctx0) => $body0" ⇒ cq"${sh.id} => ${replaceIdents(body0, ctx0.name → ctx)}" :: acc
          }
        }
      }
      if (cases.size > 1) {
        val res = freshName("res")
        val extraLine = stateHandlers.get("awaitingXStart") match {
          case Some(sh) ⇒ q"setIntercepting($res == ${sh.id})"
          case None     ⇒ q"()"
        }
        q"""protected override def _xSeal($ctx: swave.core.impl.RunContext): State = {
              val $res = ${switch("xSeal", cases)}
              $extraLine
              $res
            }"""
      } else q"()"
    }

    def xStartDef = {
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._xStart()" :: Nil) { (acc, sh) ⇒
          sh.xStart.fold(acc) { case q"() => $body" ⇒ cq"${sh.id} => $body" :: acc }
        }
      }
      if (cases.size > 1) q"protected override def _xStart(): State = ${switch("xStart", cases)}" else q"()"
    }

    def xRunDef = {
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._xRun()" :: Nil) { (acc, sh) ⇒
          sh.xRun.fold(acc) { case q"() => $body" ⇒ cq"${sh.id} => $body" :: acc }
        }
      }
      if (cases.size > 1) q"protected override def _xRun(): State = ${switch("xRun", cases)}" else q"()"
    }

    def xCleanUpDef = {
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._xCleanUp()" :: Nil) { (acc, sh) ⇒
          sh.xCleanUp.fold(acc) { tree ⇒
            val caseBody = tree match {
              case q"() => $body" ⇒ body
              case x              ⇒ q"$x()"
            }
            cq"${sh.id} => $caseBody" :: acc
          }
        }
      }
      if (cases.size > 1) q"protected override def _xCleanUp(): State = ${switch("xCleanUp", cases)}" else q"()"
    }

    List(subscribeDef, requestDef, cancelDef, onSubscribeDef, onNextDef, onCompleteDef, onErrorDef,
      xSealDef, xStartDef, xRunDef, xCleanUpDef)
  }

  def assignInterceptingStates: Tree = {
    val bitMask = stateHandlers.valuesIterator.foldLeft(0) { (acc, sh) ⇒
      if (sh.intercept) acc | (1 << sh.id) else acc
    }
    q"interceptingStates = $bitMask"
  }

  def stateNameImpl: List[DefDef] = {
    val cases = cq"_ => super.stateName" ::
      stateHandlers.foldLeft(cq"""0 => "STOPPED"""" :: Nil) { case (acc, (name, sh)) ⇒ cq"${sh.id} => $name" :: acc }

    q"override def stateName: String = stateName(stay())" ::
      q"private def stateName(id: Int): String = id match { case ..${cases.reverse} }" :: Nil
  }

  def stateCallVarAssignments(pos: Position, params: List[ValDef], args: List[Tree]): List[Tree] =
    if (params.size == args.size) {
      params.zip(args) flatMap {
        case (ValDef(_, TermName(n0), _, _), expr) ⇒
          val n = "__" + n0
          expr match {
            case Ident(TermName(`n`)) ⇒ Nil // drop self-assignment
            case AssignOrNamedArg(Ident(TermName(`n`)), rhs) ⇒ q"${TermName(n)}=$rhs" :: Nil
            case _ ⇒ q"${TermName(n)}=$expr" :: Nil
          }
      }
    } else List(Ident(TermName("argumentToParameterCountMismatch"))) // trigger error but allow dump of generated code

  def transformParamAccess(parameterSet: ParameterSet, t: Tree): Tree =
    transformTree(t) {
      case Ident(TermName(n)) if parameterSet contains n ⇒ Ident(TermName("__" + n))
    }

  def trace(s: ⇒ Any) = if (debugMode) c.echo(NoPosition, s.toString)

  case class StateDef(tree: Tree, name: String, params: List[ValDef], body: Tree)

  object StateDef {
    def unapply(tree: Tree): Option[StateDef] = {
      def sdb(name: TermName, params: List[ValDef], body: Tree) =
        body match {
          case StateDefBody(_, _) ⇒ Some(StateDef(tree, name.decodedName.toString, params, body))
          case _                  ⇒ None
        }
      tree match {
        case q"$mods def $name(..$params) = $body" ⇒ sdb(name, params, body)
        case q"$mods def $name(..$params): State = $body" ⇒ sdb(name, params, body)
        case _ ⇒ None
      }
    }
  }

  object StateDefBody {
    def unapply(tree: Tree): Option[(List[DefDef], List[Tree])] =
      tree match {
        case q"{ ..$stats; state(..$handlers) }" ⇒
          val auxDefs = stats.collect { case x @ DefDef(_, _, _, _, _, _) ⇒ x }
          if (auxDefs.size == stats.size) Some(auxDefs → handlers) else None
        case q"state(..$handlers)" ⇒ Some(Nil → handlers)
        case _                     ⇒ None
      }
  }

  case class StateHandlers(id: Int, params: List[ValDef], intercept: Boolean,
    subscribe: Option[Tree], request: Option[Tree], cancel: Option[Tree],
    onSubscribe: Option[Tree], onNext: Option[Tree], onComplete: Option[Tree], onError: Option[Tree],
    xSeal: Option[Tree], xStart: Option[Tree], xRun: Option[Tree], xCleanUp: Option[Tree])

  object StateHandlers {
    def apply(id: Int, params: List[ValDef], handlers: List[Tree]): StateHandlers = {
      val args = handlers.foldLeft(Map.empty[String, Tree]) {
        case (m, q"${ ref: RefTree } = $value") ⇒ m.updated(ref.name.decodedName.toString, value)
        case (_, x)                             ⇒ c.abort(x.pos, "All arguments to `state` must be named!")
      }
      val intercept = args.get("intercept") match {
        case None | Some(q"true") ⇒ true
        case Some(q"false")       ⇒ false
        case Some(x)              ⇒ c.abort(x.pos, "`intercept` argument must be literal `true` or `false`")
      }
      StateHandlers(id, params, intercept,
        args.get("subscribe"), args.get("request"), args.get("cancel"),
        args.get("onSubscribe"), args.get("onNext"), args.get("onComplete"), args.get("onError"),
        args.get("xSeal"), args.get("xStart"), args.get("xRun"), args.get("xCleanUp"))
    }
  }

  case class StateParentDef(tree: Tree, name: String, params: List[ValDef], defDefs: List[DefDef], entryCall: Tree)

  object StateParentDef {
    def unapply(tree: Tree): Option[StateParentDef] = {
      def spd(name: TermName, params: List[ValDef], defs: List[Tree], entryCall: Tree) =
        if (defs.exists(StateDef.unapply(_).isDefined)) {
          val defDefs = defs.collect { case x @ DefDef(_, _, _, _, _, _) ⇒ x }
          if (defDefs.size < defs.size) {
            val offending = defs.find(t ⇒ !defDefs.contains(t)).get
            c.abort(offending.pos, s"Illegal statement/definition in State Parent Def `$name`")
          }
          entryCall match {
            case Apply(Ident(TermName(n)), args) if defDefs.exists(_.name.decodedName.toString == n) ⇒
              Some(StateParentDef(tree, name.decodedName.toString, params, defDefs, entryCall))
            case x ⇒ c.abort(x.pos, s"Illegal entry call in State Parent Def `$name`")
          }
        } else None
      tree match {
        case q"def $name(..$params): State = { ..$defs; $entryCall }" ⇒ spd(name, params, defs, entryCall)
        case q"def $name(..$params) = { ..$defs; $entryCall }" ⇒ spd(name, params, defs, entryCall)
        case _ ⇒ None
      }
    }
  }
}