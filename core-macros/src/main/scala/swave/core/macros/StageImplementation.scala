/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.macros

import scala.annotation.{compileTimeOnly, StaticAnnotation}

/**
  * @param fullInterceptions if true then interceptions will also keep the signal sender, otherwise this info is dropped
  * @param dump if true printlns the generates code on the console
  * @param trace if true printlns tracing log statements around event handlers
  */
@compileTimeOnly("Unresolved @StageImplementation")
private[swave] final class StageImplementation(fullInterceptions: Boolean = false,
                                               dump: Boolean = false,
                                               trace: Boolean = false)
    extends StaticAnnotation {

  def macroTransform(annottees: Any*): Any = macro StageImplementationMacro.generateStage
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
private[swave] class StageImplementationMacro(val c: scala.reflect.macros.whitebox.Context)
    extends Util
    with ConnectFanInAndSealWith
    with ConnectFanOutAndSealWith
    with ConnectInAndSealWith
    with ConnectInOutAndSealWith
    with ConnectOutAndSealWith {
  import c.universe._

  var stateHandlers              = Map.empty[String, StateHandlers]
  val fullInterceptions: Boolean = annotationFlag("fullInterceptions")
  val debugMode: Boolean         = annotationFlag("dump")
  val tracing: Boolean           = annotationFlag("trace")

  private def annotationFlag(flag: String) =
    c.prefix.tree match {
      case q"new StageImplementation(..$params)" ⇒
        params.collectFirst { case AssignOrNamedArg(Ident(TermName(`flag`)), Literal(Constant(true))) ⇒ }.isDefined
      case _ ⇒ false
    }

  def generateStage(annottees: c.Expr[Any]*): c.Expr[Any] =
    annottees.map(_.tree) match {
      case (stageClass: ClassDef) :: Nil                           ⇒ transformStageImpl(stageClass, None)
      case (stageClass: ClassDef) :: (companion: ModuleDef) :: Nil ⇒ transformStageImpl(stageClass, Some(companion))
      case (stageObj: ModuleDef) :: Nil                            ⇒ transformStageImpl(stageObj, None)
      case other :: Nil ⇒
        c.abort(
          c.enclosingPosition,
          "@StageImplementation can only be applied to classes inheriting from swave.core.impl.stages.Stage")
    }

  def transformStageImpl(stageImpl: ImplDef, companion: Option[ModuleDef]): c.Expr[Any] = {
    val sc1             = expandConnectingStates(stageImpl)
    val stateParentDefs = sc1.impl.body collect { case StateParentDef(x) ⇒ x }
    val parameterSet    = determineParameterSet(stateParentDefs, stateDefs(sc1))
    val sc2             = stateParentDefs.foldLeft(sc1: Tree)(removeStateParentDef(parameterSet))
    val sc3             = stateDefs(sc2).foldLeft(sc2)(transformStateDef(parameterSet))
    val sc4 =
      addMembers(sc3, varMembers(parameterSet), interceptingStates(stageImpl) :: stateNameImpl ::: signalHandlersImpl)
    val sc5 = transformStateCallSites(sc4)

    val transformedStageImpl = sc5
    val result = c.Expr {
      companion match {
        case Some(comp) ⇒ q"""
          $transformedStageImpl
          $comp"""
        case None       ⇒ transformedStageImpl
      }
    }
    if (debugMode) trace(s"\n******** Generated stage ${stageImpl.name} ********\n\n" + showCode(result.tree))
    result
  }

  def expandConnectingStates(stageImpl: Tree): ImplDef =
    mapTemplate(stageImpl) {
      mapBody {
        _ flatMap {
          case q"connectFanInAndSealWith { $f }"                   ⇒ connectFanInAndSealWith(f)
          case q"connectFanOutAndSealWith { $f }"                  ⇒ connectFanOutAndSealWith(f)
          case q"connectInAndSealWith { $f }"                      ⇒ connectInAndSealWith(f)
          case q"connectInOutAndSealWith { $f }"                   ⇒ connectInOutAndSealWith(f, autoPropagate = true)
          case q"connectInOutAndSealWith_NoAutoPropagation { $f }" ⇒ connectInOutAndSealWith(f, autoPropagate = false)
          case q"connectOutAndSealWith { $f }"                     ⇒ connectOutAndSealWith(f)
          case x                                                   ⇒ x :: Nil
        }
      }
    }

  type ParameterSet = Map[TermName, Tree] // parameter -> tpt (type tree)

  def determineParameterSet(stateParentDefs: List[StateParentDef], stateDefs: List[StateDef]): ParameterSet = {
    def addParamNames(set: ParameterSet, params: List[ValDef]) =
      params.foldLeft(set) { (set, p) ⇒
        val name = p.name
        set.get(name) match {
          case None ⇒ set.updated(name, p.tpt)
          case Some(tpt) ⇒
            if (p.tpt.toString != tpt.toString) {
              c.abort(
                p.pos,
                s"State definitions show parameter `$name` with two different types: `${p.tpt}` and `$tpt`")
            } else set
        }
      }
    def addStateDefParams(set: ParameterSet, sds: List[StateDef]) =
      sds.foldLeft(set) { (set, sd) ⇒
        addParamNames(set, sd.params)
      }

    val res0 = stateParentDefs.foldLeft(Map.empty[TermName, Tree]) { (set, spd) ⇒
      addStateDefParams(addParamNames(set, spd.params), spd.defDefs.collect(scala.Function.unlift(StateDef.unapply)))
    }
    addStateDefParams(res0, stateDefs)
  }

  def stateDefs(stageClass: Tree): List[StateDef] =
    stageClass.asInstanceOf[ImplDef].impl.body collect { case StateDef(x) ⇒ x }

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
        val StateDefBody(requires, auxDefs, handlers) = transformParamAccess(parameterSet, body)
        val stateId                                   = stateHandlers.size + 1
        stateHandlers = stateHandlers.updated(name, StateHandlers(stateId, params, handlers))
        val state = q"private def ${TermName(name)}(): State = { ..$requires; $stateId }"
        exblock(state :: auxDefs.map(markPrivate))
      case x @ DefDef(_, _, _, _, _, _) ⇒ x // don't descend into other methods
    }
  }

  def transformStateCallSites(stageClass: Tree) =
    transformTree(stageClass) {
      case tree @ Apply(Ident(TermName(name)), args) if stateHandlers contains name ⇒
        unblock(stateCallVarAssignments(tree.pos, stateHandlers(name).params, args), q"${TermName(name)}()")
    }

  def addMembers(stageImpl: Tree, prepend: List[ValDef], append: List[Tree]) =
    mapTemplate(stageImpl)(mapBody(prepend ::: _ ::: append))

  def mapBody(f: List[Tree] ⇒ List[Tree]): Template ⇒ Template = {
    case Template(parents, self, body) ⇒ Template(parents, self, f(body))
  }

  def mapTemplate(stageImpl: Tree)(f: Template ⇒ Template): ImplDef =
    stageImpl match {
      case ClassDef(mods, name, typeDefs, template) ⇒ ClassDef(mods, name, typeDefs, f(template))
      case ModuleDef(mods, name, template)          ⇒ ModuleDef(mods, name, f(template))
    }

  def varMembers(parameterSet: ParameterSet): List[ValDef] =
    parameterSet.map { case (name, tpt) ⇒ q"private[this] var ${TermName("__" + name)}: $tpt = _" }(
      collection.breakOut)

  def signalHandlersImpl: List[Tree] = {

    def withDefault(cs: Seq[Tree], default: Tree): Seq[Tree] =
      if (cs exists { case cq"_ => ${_}" ⇒ true; case _ ⇒ false }) cs else cs :+ default

    def compact(cases: List[CaseDef]): List[CaseDef] =
      cases
        .foldLeft(List.empty[CaseDef]) { (acc, cd) ⇒
          acc indexWhere { case cq"${_} => $body" ⇒ body equalsStructure cd.body } match {
            case -1 ⇒ cd :: acc
            case ix ⇒
              val altPat = acc(ix) match {
                case cq"$head | ..$tail => ${_}" ⇒ pq"${cd.pat} | ..${head :: tail}"
                case cq"$pat => ${_}"            ⇒ pq"${cd.pat} | $pat"
              }
              acc.updated(ix, cq"$altPat => ${cd.body}")
          }
        }
        .reverse

    def switch(signal: String, cases: List[CaseDef], params: TermName*): Tree = {
      val theMatch = q"stay() match { case ..$cases }"
      if (tracing) {
        val res = freshName("res")
        q"""println(this + ${Literal(Constant(s": entering `$signal"))} + List(..$params).mkString("(", ", ", ")`"))
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
        q"""final protected override def _subscribe0($from: swave.core.impl.Outport): State =
           ${switch("subscribe", cases, from)}"""
      else q"()"
    }

    def requestDef = {
      val n    = freshName("n")
      val from = freshName("from")
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._request0($n, $from)" :: Nil) { (acc, sh) ⇒
          sh.request.fold(acc) { tree ⇒
            val caseBody = tree match {
              case q"($n0, $from0) => $body0" ⇒ replaceIdents(body0, n0.name → n, from0.name → from)
              case x                          ⇒ q"$x($n, $from)"
            }
            cq"${sh.id} => $caseBody" :: acc
          }
        }
      }
      if (cases.size > 1) {
        q"""final protected override def _request0($n: Int, $from: swave.core.impl.Outport): State =
           ${switch("request", cases, n, from)}"""
      } else q"()"
    }

    def cancelDef = {
      val from = freshName("from")
      val cases = compact {
        val default = cq"_ => super._cancel0($from)"
        stateHandlers.valuesIterator.foldLeft(default :: Nil) { (acc, sh) ⇒
          sh.cancel.fold(acc) { tree ⇒
            val caseBody = tree match {
              case q"($from0) => $body0" ⇒ replaceIdents(body0, from0.name → from)
              case q"{ case ..$cs }"     ⇒ q"$from match { case ..${withDefault(cs, default)} }"
              case x                     ⇒ q"$x($from)"
            }
            cq"${sh.id} => $caseBody" :: acc
          }
        }
      }
      if (cases.size > 1) {
        q"""final protected override def _cancel0($from: swave.core.impl.Outport): State =
           ${switch("cancel", cases, from)}"""
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
        q"""final protected override def _onSubscribe0($from: swave.core.impl.Inport): State =
           ${switch("onSubscribe", cases, from)}"""
      } else q"()"
    }

    def onNextDef = {
      val elem = freshName("elem")
      val from = freshName("from")
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._onNext0($elem, $from)" :: Nil) { (acc, sh) ⇒
          sh.onNext.fold(acc) { tree ⇒
            val caseBody = tree match {
              case q"($elem0, $from0) => $body0" ⇒ replaceIdents(body0, elem0.name → elem, from0.name → from)
              case x                             ⇒ q"$x($elem, $from)"
            }
            cq"${sh.id} => $caseBody" :: acc
          }
        }
      }
      if (cases.size > 1) {
        q"""final protected override def _onNext0($elem: AnyRef, $from: swave.core.impl.Inport): State =
           ${switch("onNext", cases, elem, from)}"""
      } else q"()"
    }

    def onCompleteDef = {
      val from = freshName("from")
      val cases = compact {
        val default = cq"_ => super._onComplete0($from)"
        stateHandlers.valuesIterator.foldLeft(default :: Nil) { (acc, sh) ⇒
          sh.onComplete.fold(acc) { tree ⇒
            val caseBody = tree match {
              case q"($from0) => $body0" ⇒ replaceIdents(body0, from0.name → from)
              case q"{ case ..$cs }"     ⇒ q"$from match { case ..${withDefault(cs, default)} }"
              case x                     ⇒ q"$x($from)"
            }
            cq"${sh.id} => $caseBody" :: acc
          }
        }
      }
      if (cases.size > 1) {
        q"""final protected override def _onComplete0($from: swave.core.impl.Inport): State =
           ${switch("onComplete", cases, from)}"""
      } else q"()"
    }

    def onErrorDef = {
      val error = freshName("error")
      val from  = freshName("from")
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
        q"""final protected override def _onError0($error: Throwable, $from: swave.core.impl.Inport): State =
           ${switch("onError", cases, error, from)}"""
      } else q"()"
    }

    def xSealDef = {
      val cases = compact {
        stateHandlers.valuesIterator.foldLeft(cq"_ => super._xSeal()" :: Nil) { (acc, sh) ⇒
          sh.xSeal.fold(acc) { case q"() => $body" ⇒ cq"${sh.id} => $body" :: acc }
        }
      }
      if (cases.size > 1) {
        val res = freshName("res")
        val extraLine =
          stateHandlers.valuesIterator
            .filter(sh => sh.xStart.isDefined && sh.intercept)
            .map(sh => q"$res == ${sh.id}")
            .toList match {
            case Nil => q"()"
            case x   => q"setIntercepting(${x.reduceLeft((a, b) => q"$a || $b")})"
          }
        q"""final protected override def _xSeal(): State = {
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
      if (cases.size > 1) q"final protected override def _xStart(): State = ${switch("xStart", cases)}" else q"()"
    }

    def xEventDef = {
      val ev = freshName("ev")
      val cases = compact {
        val default = cq"_ => super._xEvent0($ev)"
        stateHandlers.valuesIterator.foldLeft(default :: Nil) { (acc, sh) ⇒
          sh.xEvent.fold(acc) {
            case q"($ev0) => $body0" ⇒ cq"${sh.id} => ${replaceIdents(body0, ev0.name → ev)}" :: acc
            case q"{ case ..$cs }"   ⇒ cq"${sh.id} => $ev match { case ..${withDefault(cs, default)} }" :: acc
          }
        }
      }
      if (cases.size > 1) {
        q"""final protected override def _xEvent0($ev: AnyRef): State =
           ${switch("xEvent", cases, ev)}"""
      } else q"()"
    }

    List(
      subscribeDef,
      requestDef,
      cancelDef,
      onSubscribeDef,
      onNextDef,
      onCompleteDef,
      onErrorDef,
      xSealDef,
      xStartDef,
      xEventDef)
  }

  def interceptingStates(stageImpl: ImplDef): Tree = {
    if (stateHandlers.size > 31)
      c.abort(
        stageImpl.pos,
        s"A stage implementation must not have more than 31 states! (Here it has ${stateHandlers.size})")
    var bitMask = stateHandlers.valuesIterator.foldLeft(0) { (acc, sh) ⇒
      if (sh.intercept) acc | (1 << sh.id) else acc
    }
    if (fullInterceptions) bitMask |= Int.MinValue // the highest bit signals whether we need full interceptions
    q"interceptingStates = $bitMask"
  }

  def stateNameImpl: List[DefDef] = {
    val cases = cq"_ => super.stateName" ::
        stateHandlers.foldLeft(cq"""0 => "STOPPED"""" :: Nil) { case (acc, (name, sh)) ⇒ cq"${sh.id} => $name" :: acc }

    q"final override def stateName: String = stateName(stay())" ::
      q"private def stateName(id: Int): String = id match { case ..${cases.reverse} }" :: Nil
  }

  def stateCallVarAssignments(pos: Position, params: List[ValDef], args: List[Tree]): List[Tree] =
    if (params.size == args.size) {
      params.zip(args) flatMap {
        case (ValDef(_, TermName(n0), _, _), expr) ⇒
          val n = "__" + n0
          expr match {
            case Ident(TermName(`n`))                        ⇒ Nil // drop self-assignment
            case AssignOrNamedArg(Ident(TermName(`n`)), rhs) ⇒ q"${TermName(n)}=$rhs" :: Nil
            case _                                           ⇒ q"${TermName(n)}=$expr" :: Nil
          }
      }
    } else {
      // trigger error but allow dump of generated code
      List(Ident(TermName(s"argumentToParameterCountMismatchInLine${pos.line}")))
    }

  def transformParamAccess(parameterSet: ParameterSet, t: Tree): Tree =
    replaceIdents(t, parameterSet map { case (name, _) ⇒ name → TermName("__" + name) })

  def trace(s: ⇒ Any) = if (debugMode) c.echo(NoPosition, s.toString)

  case class StateDef(tree: Tree, name: String, params: List[ValDef], body: Tree)

  object StateDef {
    def unapply(tree: Tree): Option[StateDef] = {
      def sdb(name: TermName, params: List[ValDef], body: Tree) =
        body match {
          case StateDefBody(_, _, _) ⇒ Some(StateDef(tree, name.decodedName.toString, params, body))
          case _                     ⇒ None
        }
      tree match {
        case q"$mods def $name(..$params) = $body"        ⇒ sdb(name, params, body)
        case q"$mods def $name(..$params): State = $body" ⇒ sdb(name, params, body)
        case _                                            ⇒ None
      }
    }
  }

  case class StateDefBody(requires: List[Tree], auxDefs: List[DefDef], handlers: List[Tree]) {
    require(handlers.nonEmpty)
  }

  object StateDefBody {
    def unapply(tree: Tree): Option[(List[Tree], List[DefDef], List[Tree])] =
      tree match {
        case q"{ ..$stats; state(..$handlers) }" ⇒
          val allowedPreTrees = stats.collect {
            case x @ q"requireState(..${_})"  ⇒ Left(x)
            case x @ DefDef(_, _, _, _, _, _) ⇒ Right(x)
          }
          if (allowedPreTrees.size == stats.size) {
            val requires = allowedPreTrees collect { case Left(x)  ⇒ x }
            val auxDefs  = allowedPreTrees collect { case Right(x) ⇒ x }
            Some((requires, auxDefs, handlers))
          } else None
        case q"state(..$handlers)" ⇒ Some((Nil, Nil, handlers))
        case _                     ⇒ None
      }
  }

  case class StateHandlers(id: Int,
                           params: List[ValDef],
                           intercept: Boolean,
                           subscribe: Option[Tree],
                           request: Option[Tree],
                           cancel: Option[Tree],
                           onSubscribe: Option[Tree],
                           onNext: Option[Tree],
                           onComplete: Option[Tree],
                           onError: Option[Tree],
                           xSeal: Option[Tree],
                           xStart: Option[Tree],
                           xEvent: Option[Tree])

  object StateHandlers {
    private val definedSignals = Seq(
      "intercept",
      "subscribe",
      "request",
      "cancel",
      "onSubscribe",
      "onNext",
      "onComplete",
      "onError",
      "xSeal",
      "xStart",
      "xEvent")

    def apply(id: Int, params: List[ValDef], handlers: List[Tree]): StateHandlers = {
      val args = handlers.foldLeft(Map.empty[String, Tree]) {
        case (m, x @ q"${ref: RefTree} = $value") ⇒
          val name = ref.name.decodedName.toString
          if (!definedSignals.contains(name)) c.abort(x.pos, s"Unknown signal `$name`!")
          m.updated(name, value)
        case (_, x) ⇒ c.abort(x.pos, "All arguments to `state` must be named!")
      }
      val intercept = args.get("intercept") match {
        case None | Some(q"true") ⇒ true
        case Some(q"false")       ⇒ false
        case Some(x)              ⇒ c.abort(x.pos, "`intercept` argument must be literal `true` or `false`")
      }
      StateHandlers(
        id,
        params,
        intercept,
        args.get("subscribe"),
        args.get("request"),
        args.get("cancel"),
        args.get("onSubscribe"),
        args.get("onNext"),
        args.get("onComplete"),
        args.get("onError"),
        args.get("xSeal"),
        args.get("xStart"),
        args.get("xEvent"))
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
        case q"def $name(..$params) = { ..$defs; $entryCall }"        ⇒ spd(name, params, defs, entryCall)
        case _                                                        ⇒ None
      }
    }
  }
}
