/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.testkit.gen

import org.scalacheck.{ Gen, Prop }
import scala.util.control.NonFatal
import shapeless.ops.function.FnToProduct
import shapeless.ops.hlist.{ Reverse, Tupler }
import shapeless._
import swave.testkit.impl._
import swave.core.PipeElem
import swave.core.impl.stages.Stage
import swave.core.util._
import swave.core.macros._

object TestSetup {

  def newDslRoot: TestSetupDef = new TestSetupDefImpl(Default.asyncRates, Default.asyncSchedulings, tracing = false)

  sealed abstract class TestSetupDef extends MainDef0[HNil] {
    def withAsyncRates(asyncRates: Gen[Double]): TestSetupDef
    def withAsyncSchedulings(asyncRates: Gen[AsyncScheduling]): TestSetupDef
    def withTracing(): TestSetupDef
  }

  sealed abstract class MainDef0[L <: HList] {
    final def input[T](implicit elems: Gen[T]): MainDef[TestInput[T] :: L] =
      fixture(_.input(elems))
    final def inputFromIterables[T](iterables: Gen[Iterable[T]]): MainDef[TestInput[T] :: L] =
      fixture(_.inputFromIterables(iterables))
    final def inputFromScripts[T](scripts: Gen[InputScript[T]]): MainDef[TestInput[T] :: L] =
      fixture(_.inputFromScripts(scripts))
    final def output[T]: MainDef[TestOutput[T] :: L] =
      fixture(_.output[T])
    def param[T](implicit gen: Gen[T]): MainDef[T :: L]
    def fixture[T](f: FixtureDef ⇒ Gen[T]): MainDef[T :: L]

    final def fixtures[T](counts: Gen[Int], f: FixtureDef ⇒ Gen[T]): MainDef[List[T] :: L] =
      fixture(fd ⇒ counts.flatMap(Gen.listOfN(_, f(fd))))
  }

  sealed abstract class MainDef[L <: HList] extends MainDef0[L] {
    def gen[R <: HList, T](implicit rev: Reverse.Aux[L, R], tup: Tupler.Aux[R, T]): Gen[T]
    def prop[R <: HList, F](implicit rev: Reverse.Aux[L, R], fn: FnToProduct.Aux[F, R ⇒ Unit]): Propper[F]
  }

  sealed abstract class FixtureDef {
    def input[T](implicit elems: Gen[T]): Gen[TestInput[T]]
    def input[T](elems: Gen[T], terminations: Gen[Option[Throwable]]): Gen[TestInput[T]]
    def inputFromIterables[T](
      elemSeqs: Gen[Iterable[T]],
      terminations: Gen[Option[Throwable]] = Default.terminations): Gen[TestInput[T]]
    def inputFromScripts[T](scripts: Gen[InputScript[T]]): Gen[TestInput[T]]
    def output[T](implicit scripts: Gen[OutputScript] = Default.defaultOutputScripts): Gen[TestOutput[T]]
  }

  sealed abstract class Propper[F] {
    def from(f: F): Prop
    def withRandomSeed(seed: String): Propper[F]
  }

  sealed abstract class AsyncScheduling
  object AsyncScheduling {
    case object InOrder extends AsyncScheduling
    case object RandomOrder extends AsyncScheduling
    case object ReversedOrder extends AsyncScheduling
    case object Mixed extends AsyncScheduling
  }

  /**
   * @param elems the elements to produce
   * @param termination the type of termination to perform after the last element
   */
  case class InputScript[+T](elems: Iterable[T], termination: Option[Throwable])

  /**
   * @param requests the sequence of request calls to make before cancellation
   * @param cancelAfter if defined triggers an early cancel after reception of n total elements
   */
  case class OutputScript(requests: Iterable[Long], cancelAfter: Option[Int] = None) {
    requireArg(cancelAfter.isEmpty || cancelAfter.get < requests.sum)
  }

  object Default {
    val asyncRates: Gen[Double] = Gen.oneOf(0.0, 0.1, 1.0)

    val asyncSchedulings: Gen[AsyncScheduling] = {
      import AsyncScheduling._
      Gen.oneOf(InOrder, RandomOrder, ReversedOrder, Mixed)
    }

    val elemCounts: Gen[Int] = Gen.oneOf(0, 1, 2, 4, 7, 19, 31, 150)

    val elems: Gen[Int] = Gen.chooseNum(0, 999)

    def elemLists[T](elems: Gen[T]): Gen[List[T]] = elemCounts.flatMap(Gen.listOfN(_, elems))

    val terminations: Gen[Option[Throwable]] = Gen.frequency(5 → None, 1 → Some(TestError))

    val defaultOutputScripts: Gen[OutputScript] =
      Gen.oneOf(
        OutputScript(Nil),
        OutputScript(1L :: Nil, cancelAfter = Some(0)),
        OutputScript(1L :: Nil),
        OutputScript(2L :: 1L :: Nil),
        OutputScript(3L :: 2L :: 8L :: Nil, cancelAfter = Some(4)),
        OutputScript(4L :: 19L :: Nil, cancelAfter = Some(16)),
        OutputScript(Long.MaxValue :: Nil, cancelAfter = Some(131)))

    val nonDroppingOutputScripts: Gen[OutputScript] =
      Gen.oneOf(
        OutputScript(Nil),
        OutputScript(1L :: Nil),
        OutputScript(2L :: 1L :: Nil),
        OutputScript(3L :: 2L :: 8L :: Nil),
        OutputScript(4L :: 19L :: Nil))
  }

  ////////////////////////////////// DSL IMPLEMENTATION ///////////////////////////////////////////

  private class TestSetupDefImpl(asyncRates: Gen[Double], asyncSchedulings: Gen[AsyncScheduling],
      tracing: Boolean) extends TestSetupDef {
    private[this] val runCounter = Iterator from 0

    def withAsyncRates(asyncRates: Gen[Double]) = new TestSetupDefImpl(asyncRates, asyncSchedulings, tracing)
    def withAsyncSchedulings(asyncSchedulings: Gen[AsyncScheduling]) = new TestSetupDefImpl(asyncRates, asyncSchedulings, tracing)
    def withTracing() = new TestSetupDefImpl(asyncRates, asyncSchedulings, tracing = true)
    def param[T](implicit gen: Gen[T]) = finish.param[T]
    def fixture[T](f: FixtureDef ⇒ Gen[T]) = finish.fixture(f)

    private def finish = {
      val contexts =
        for {
          asyncRate ← asyncRates
          asyncScheduling ← asyncSchedulings
          random ← Gen.parameterized(params ⇒ Gen.const(params.rng))
        } yield {
          val xorShiftRandom = random match {
            case x: XorShiftRandom.Random ⇒ x.xorShiftRandom
            case _                        ⇒ XorShiftRandom()
          }
          val runNr = runCounter.next()
          new TestContext(runNr, asyncRate, asyncScheduling, xorShiftRandom, tracing && runNr == 0)
        }
      new DefImpl[HNil](contexts, Nil)
    }
  }

  private class DefImpl[L <: HList](
      contexts: Gen[TestContext],
      creatorsList: List[FixtureDef ⇒ Gen[Any]]) extends MainDef[L] {

    def param[T](implicit gen: Gen[T]): MainDef[T :: L] = fixture(_ ⇒ gen)

    def fixture[T](f: FixtureDef ⇒ Gen[T]): MainDef[T :: L] =
      new DefImpl(contexts, f :: creatorsList)

    def gen[R <: HList, T](implicit rev: Reverse.Aux[L, R], tupler: Tupler.Aux[R, T]): Gen[T] =
      revGen map { case (_, hlist) ⇒ tupler(hlist) }

    def prop[R <: HList, F](implicit rev: Reverse.Aux[L, R], fn: FnToProduct.Aux[F, R ⇒ Unit]) =
      new PropperImpl[R, F](revGen, fn.apply, "")

    private def revGen[R <: HList](implicit rev: Reverse.Aux[L, R]): Gen[(TestContext, R)] =
      for {
        ctx ← contexts
        listOfFixtureGen = creatorsList.reverse.map(_(new FixtureDefImpl(ctx)))
        listOfFixtures ← Gen.sequence[List[Any], Any](listOfFixtureGen)
      } yield ctx → listOfFixtures.foldRight(HNil: HList)(_ :: _).asInstanceOf[R]
  }

  private class FixtureDefImpl(ctx: TestContext) extends FixtureDef {
    def input[T](implicit elems: Gen[T]) = input(elems, Default.terminations)

    def input[T](elems: Gen[T], terminations: Gen[Option[Throwable]]) =
      inputFromIterables(Default.elemLists(elems), terminations)

    def inputFromIterables[T](elemSeqs: Gen[Iterable[T]], terminations: Gen[Option[Throwable]] = Default.terminations) =
      inputFromScripts {
        for {
          elems ← elemSeqs
          termination ← terminations
        } yield InputScript(elems, termination)
      }

    def inputFromScripts[T](scripts: Gen[InputScript[T]]) =
      scripts map { script ⇒
        val elems = script.elems.asInstanceOf[Iterable[AnyRef]]
        new TestInput[T](new TestStreamStage(ctx.nextId(), elems, script.termination, ctx))
      }

    def output[T](implicit scripts: Gen[OutputScript]) =
      scripts map { script ⇒
        new TestOutput[T](new TestDrainStage(ctx.nextId(), script.requests, script.cancelAfter, ctx))
      }
  }

  private class PropperImpl[L <: HList, F](gen: Gen[(TestContext, L)], convertF: F ⇒ L ⇒ Unit, seed: String) extends Propper[F] {
    def withRandomSeed(seed: String): Propper[F] = new PropperImpl[L, F](gen, convertF, seed)

    def from(f: F): Prop = Prop { params ⇒
      val random = params.rng match {
        case x: XorShiftRandom.Random if seed.isEmpty ⇒ x
        case _                                        ⇒ XorShiftRandom(seed).asScalaRandom
      }
      val prop = Prop.forAll(gen)(propFun(convertF(f), random.xorShiftRandom.seed))
      prop(params withRng random)
    }

    private def propFun(f: L ⇒ Unit, randomSeed: (Long, Long)): ((TestContext, L)) ⇒ Prop = {
      case (ctx, l) ⇒
        def filterStages(untypedList: List[Any]): List[TestStage] =
          untypedList flatMap {
            case x: TestInput[_]              ⇒ filterStages(x.elements.toList) :+ x.stage
            case x: TestOutput[_]             ⇒ x.stage :: Nil
            case x @ List(_: TestFixture, _*) ⇒ x.map(_.asInstanceOf[TestFixture].stage)
            case _                            ⇒ Nil
          }
        val untypedList = l.toUntypedList
        val testStages = filterStages(untypedList)
        try {
          f(l)
          postRunVerification(testStages)
          Prop.proved // or rather Prop.passed?
        } catch {
          case NonFatal(e) ⇒
            val graphRendering = testStages.mapFind { stage ⇒
              try Some(PipeElem.render(stage.asInstanceOf[Stage].pipeElem))
              catch { case e: IllegalStateException if e.getMessage contains "inconsistent edge data" ⇒ None }
            }
            println(graphRendering getOrElse "(no graph rendering available)")
            println()
            val params = untypedList flatMap {
              case _: TestFixture           ⇒ Nil
              case List(_: TestFixture, _*) ⇒ Nil
              case x                        ⇒ x :: Nil
            }
            val fixtures = testStages.map(s ⇒ f"${s.id}%3d: ${s.formatLong}".replace("\n", "\n       "))
            val specimens = testStages.map({
              case x: TestStreamStage ⇒ x.outputElem
              case x: TestDrainStage  ⇒ x.inputElem
            }).filter(_ != PipeElem.Unconnected).distinct
            println(
              s"""|Error Context
                  |  randomSeed     : ${XorShiftRandom.formatSeed(randomSeed)}
                  |  runNr          : ${ctx.runNr}
                  |  asyncScheduling: ${ctx.asyncScheduling}
                  |  asyncRate      : ${ctx.asyncRate}
                  |  params         : [${params.mkString(", ")}]
                  |  fixtures:
                  |  ${fixtures.mkString("\n\n  ")}
                  |
                  |  specimens:
                  |    ${specimens.mkString("\n\n    ")}""".stripMargin)
            Prop.exception(e)
        }
    }

    private def postRunVerification(stages: List[TestStage]): Unit =
      stages.find(_.fixtureState == TestFixture.State.Running) foreach { stage ⇒
        sys.error(s"Post run verification failure: unstopped $stage in stage ${stage.fixtureState}")
      }
  }
}
