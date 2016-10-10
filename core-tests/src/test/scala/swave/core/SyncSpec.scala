/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.concurrent.Promise
import swave.core.util._

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

    "cycles" in {
      val c = Coupling[Int]
      Spout(1, 2, 3)
        .concat(c.out)
        .fanOutBroadcast()
        .sub
        .first
        .buffer(1)
        .map(_ + 3)
        .to(c.in)
        .subContinue should produce(1, 2, 3, 4)
    }

    "fanout / fanInToProduct" in {
      case class Foo(s: String, d: Double, i: Int, b: Boolean)

      Spout(1, 2, 3)
        .fanOutBroadcast()
        .sub
        .buffer(4)
        .map(_.toString)
        .end
        .sub
        .buffer(4)
        .map(_ * 2.0)
        .end
        .sub
        .to(Drain.ignore.dropResult) // just for fun
        .sub
        .drop(2)
        .concat(Spout(List(4, 5)))
        .end
        .attach(Spout.repeat(true))
        .fanInToProduct[Foo] should produce(
        Foo("1", 2.0, 3, b = true),
        Foo("2", 4.0, 4, b = true),
        Foo("3", 6.0, 5, b = true))
    }

    "fanout to drain" in {
      val promise = Promise[Seq[Int]]()
      Spout(1, 2, 3).tee(Drain.seq(10).capture(promise)) should produce(1, 2, 3)
      promise.future.await() shouldEqual Seq(1, 2, 3)
    }

    "double direct fanout" in {
      Spout(1, 2, 3).fanOutBroadcast().sub.end.sub.end.fanInMerge() should produce(1, 1, 2, 2, 3, 3)
    }

    "standalone pipes" in {
      val filterEven = Pipe[Int].map(_ / 2.0).map(_.toString).filterNot(_ contains ".0")
      Spout(1, 2, 3, 4, 5).via(filterEven) should produce("0.5", "1.5", "2.5")
    }

    "simple modules" in {
      val foo = Module.Forward.from2[Int, String] { (a, b) ⇒
        a.attachN(2, b.fanOutBroadcast())
      } named "foo"
      Spout(1, 2, 3).attach(Spout("x", "y", "z")).fromFanInVia(foo).fanInToTuple.map(_.toString) should produce(
        "(1,x,x)",
        "(2,y,y)",
        "(3,z,z)")
    }

    "inject" in {
      Spout(1 to 10).inject.map(_ elementAt 1).flattenConcat() should produce(2, 4, 6, 8, 10)
    }
  }
}
