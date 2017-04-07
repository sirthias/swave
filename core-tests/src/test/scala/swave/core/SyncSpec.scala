/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import scala.concurrent.Promise
import scala.util.{Failure, Success}
import swave.core.util._

// format: OFF
class SyncSpec extends SwaveSpec {

  implicit val env = StreamEnv()

  "Fully synchronous pipings" - {

    "drainTo head" in {
      Spout(1, 2, 3).drainTo(Drain.head).value.get.get shouldEqual 1
    }

    "simple produce" in {
      Spout(1, 2, 3).map(_.toString) should produce("1", "2", "3")
    }

    "concat" in {
      Spout(1, 2, 3).concat(Spout(4, 5, 6)) should produce(1, 2, 3, 4, 5, 6)
    }

    "filter & collect" in {
      val elems = Seq.fill(32)(1) ++ Seq.fill(32)(2.0)
      Spout.fromIterable(elems)
        .fanOutBroadcast()
          .sub.collect { case x if x.isInstanceOf[Int] => x }.end
          .sub.filter(_.isInstanceOf[Double]).end
        .fanInMerge() should produce(elems: _*)
    }

    "cycles" in {
      val c = Coupling[Int]
      Spout(1, 2, 3)
        .concat(c.out)
        .fanOutBroadcast()
          .sub.first.buffer(1).map(_ + 3).to(c.in)
          .subContinue should produce(1, 2, 3, 4)
    }

    "cycles 2" in {
      val c = Coupling[Int]
      Spout.one(1)
        .concat(c.out)
        .fanOutBroadcast(eagerCancel = true)
          .sub.buffer(2, requestStrategy = Buffer.RequestStrategy.Always).map(_ + 1).to(c.in)
          .subContinue.take(10) should produce(1 to 10: _*)
    }

    "fanout / fanInToProduct" in {
      case class Foo(s: String, d: Double, i: Int, b: Boolean)

      Spout(1, 2, 3)
        .fanOutBroadcast()
          .sub.buffer(4).map(_.toString).end
          .sub.buffer(4).map(_ * 2.0).end
          .sub.to(Drain.ignore.dropResult) // just for fun
          .sub.drop(2).concat(Spout(List(4, 5))).end
          .attach(Spout.repeat(true))
        .fanInToProduct[Foo] should produce(
          Foo("1", 2.0, 3, b = true),
          Foo("2", 4.0, 4, b = true),
          Foo("3", 6.0, 5, b = true))
    }

    "fanout to drain" in {
      val promise = Promise[Seq[Int]]()
      Spout(1, 2, 3).tee(Drain.seq(10).captureResult(promise)) should produce(1, 2, 3)
      promise.future.await() shouldEqual Seq(1, 2, 3)
    }

    "fanout end" in {
      val promise = Promise[Seq[Int]]()
      Spout(1, 2, 3)
        .fanOutBroadcast()
          .sub.to(Drain.seq(10).captureResult(promise))
          .sub.to(Drain.ignore.dropResult)
        .end
        .run()
      promise.future.await() shouldEqual Seq(1, 2, 3)
    }

    "double direct fanout" in {
      Spout(1, 2, 3)
        .fanOutBroadcast()
          .sub.end
          .sub.end
        .fanInMerge() should produce(1, 1, 2, 2, 3, 3)
    }

    "standalone pipes" in {
      val filterEven = Pipe[Int].map(_ / 2.0).map(_.toString).filterNot(_ contains ".0")
      Spout(1, 2, 3, 4, 5).via(filterEven) should produce("0.5", "1.5", "2.5")
    }

    "simple modules" in {
      val foo = Module.Forward.from2[Int, String] { (a, b) ⇒
        a.attachN(2, b.fanOutBroadcast())
      } named "foo"
      Spout(1, 2, 3)
        .attach(Spout("x", "y", "z"))
        .fromFanInVia(foo)
        .fanInToTuple
        .map(_.toString) should produce(
        "(1,x,x)",
        "(2,y,y)",
        "(3,z,z)")
    }

    "injectSequential" in {
      Spout(1 to 50).takeEveryNth(10) should produce(10, 20, 30, 40, 50)
    }

    "nested substreams" in {
      Spout
        .ints(0)
        .injectSequential()
        .flatMap {
          _
            .take(8)
            .injectSequential()
            .flatMap {
              _
                .take(4)
                .injectSequential()
                .flatMap(_.take(2))
            }
        }
        .take(16) should produce(0 to 15: _*)
    }

//    "external sub-stream start" in {
//      implicit val env = StreamEnv(mapSettings = _.withSubscriptionTimeout(Duration.Undefined))
//      val spout: Spout[Int] = Spout.ints(0).injectSequential().drainToHead().value.get.get
//      spout.take(3) should produce(0, 1, 3)
//    }
  }

  "Illegal usage" - {

    "illegal reconnect" in {
      val spout = Spout.one(42)
      spout.map(_.toString)
      val thrown = the[IllegalReuseException] thrownBy spout.map(_ + 1)
      thrown.getMessage should startWith("Downstream already connected in IteratorSpoutStage")
      thrown.getMessage should endWith("Are you trying to reuse a stage instance?")
    }

    "illegal reseal" in {
      val streamGraph = Spout.one(42).to(Drain.head)
      streamGraph.trySeal() shouldBe a[Success[_]]
      inside(streamGraph.trySeal()) {
        case Failure(e: IllegalReuseException) ⇒
          e.getMessage should include("is already sealed. It cannot be sealed a second time. " +
            "Are you trying to reuse a Spout, Drain, Pipe or Module?")
      }
    }

    "illegal open port" in {
      val thrown = the[UnclosedStreamGraphException] thrownBy Coupling[Int].out.drainToBlackHole().value.get.get
      thrown.getMessage should startWith("Unconnected upstream in CouplingStage")
    }

    "illegal restart" in {
      val spout = Spout.one(42)
      val first = spout.drainToBlackHole()
      val second = spout.to(Drain.ignore).trySeal()
      first.value shouldEqual Some(Success(()))
      inside(second) {
        case Failure(e: IllegalReuseException) ⇒
          e.getMessage should endWith("Are you trying to reuse a Spout, Drain, Pipe or Module?")
      }
    }

    "illegal non-terminating" in {
      val c = Coupling[Int]
      val result = Spout.one(1)
        .concat(c.out)
        .fanOutBroadcast()
          .sub.to(c.in)
          .subContinue
        .drainToBlackHole()
      inside(result.value) {
        case Some(Failure(_: UnterminatedSynchronousStreamException)) ⇒ // ok
      }
    }
  }
}
