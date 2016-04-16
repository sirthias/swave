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

package swave.core

import scala.concurrent.duration.Duration
import scala.concurrent.Promise
import swave.core.util._

class SyncSpec extends SwaveSpec {

  implicit val env = StreamEnv()

  "Fully synchronous pipings" - {

    "drainTo head" in {
      Stream(1, 2, 3).drainTo(Drain.head).await(Duration.Zero) shouldEqual 1
    }

    "simple produce" in {
      Stream(1, 2, 3).map(_.toString) should produce("1", "2", "3")
    }

    "concat" in {
      Stream(1, 2, 3).concat(Stream(4, 5, 6)) should produce(1, 2, 3, 4, 5, 6)
    }

    "cycles" in {
      val c = Coupling[Int]
      Stream(1, 2, 3)
        .concat(c.out)
        .fanOut().sub.first.buffer(1).map(_ + 3).to(c.in)
        .subContinue should produce(1, 2, 3, 4)
    }

    "fanout / fanInToProduct" in {
      case class Foo(s: String, d: Double, i: Int, b: Boolean)

      Stream(1, 2, 3)
        .fanOut()
        .sub.buffer(4).map(_.toString).end
        .sub.buffer(4).map(_ * 2.0).end
        .sub.to(Drain.ignore.dropResult) // just for fun
        .sub.drop(2).concat(Stream(List(4, 5))).end
        .attach(Stream.repeat(true))
        .fanInToProduct[Foo] should produce(
          Foo("1", 2.0, 3, b = true),
          Foo("2", 4.0, 4, b = true),
          Foo("3", 6.0, 5, b = true))
    }

    "fanout to drain" in {
      val promise = Promise[Seq[Int]]()
      Stream(1, 2, 3).tee(Drain.seq(10).capture(promise)) should produce(1, 2, 3)
      promise.future.await() shouldEqual Seq(1, 2, 3)
    }

    "double direct fanout" in {
      Stream(1, 2, 3)
        .fanOut()
        .sub.end
        .sub.end
        .fanInMerge() should produce(1, 1, 2, 2, 3, 3)
    }

    "standalone pipes" in {
      val filterEven = Pipe[Int].map(_ / 2.0).map(_.toString).filterNot(_ contains ".0")
      Stream(1, 2, 3, 4, 5).via(filterEven) should produce("0.5", "1.5", "2.5")
    }

    "simple modules" in {
      val foo = Module.Forward.from2[Int, String] { (a, b) ⇒ a.attachN(2, b.fanOut()) } named "foo"
      Stream(1, 2, 3)
        .attach(Stream("x", "y", "z"))
        .fromFanInVia(foo)
        .fanInToTuple
        .map(_.toString) should produce("(1,x,x)", "(2,y,y)", "(3,z,z)")
    }

    "inject" in {
      Stream(1 to 10)
        .inject
        .map(_ elementAt 1)
        .flattenConcat() should produce(2, 4, 6, 8, 10)
    }
  }
}
