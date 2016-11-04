/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.util

import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FreeSpec, Matchers}
import swave.core.impl.util.RingBuffer

class RingBufferSpec extends FreeSpec with Matchers with GeneratorDrivenPropertyChecks {

  "A RingBuffer should" - {

    val bufferGen = for {
      bit ← Gen.choose(0, 8)
    } yield new RingBuffer[String](cap = 1 << bit)

    "take in exactly `capacity` elements" in {
      forAll(bufferGen) { buf ⇒
        val a = Stream.continually("x").takeWhile(buf.write).toArray
        a.length shouldEqual buf.capacity
      }
    }

    "read back exactly the number of elems previously written" in {
      val gen = for {
        buf   ← bufferGen
        count ← Gen.choose(0, buf.capacity)
      } yield (buf, count)

      forAll(gen) {
        case (buf, count) ⇒
          val values = List.tabulate(count)(_.toString)
          values.foreach(s ⇒ buf.write(s) shouldBe true)
          List.fill(count)(buf.read()) shouldEqual values
          buf.isEmpty shouldBe true
          a[NoSuchElementException] should be thrownBy buf.read()
      }
    }

    "pass a simple stress-test" in {
      val gen = for {
        buf     ← bufferGen
        opCount ← Gen.choose(5, 20)
        ops     ← Gen.listOfN(opCount, Gen.choose(-10, 20))
      } yield (buf, ops)

      forAll(gen) {
        case (buf, ops) ⇒
          val queue = collection.mutable.Queue[String]()
          val ints  = Iterator.from(0)
          ops foreach {
            case readCount if readCount < 0 ⇒
              -readCount times {
                buf.isEmpty shouldEqual queue.isEmpty
                if (queue.nonEmpty) queue.dequeue() shouldEqual buf.read()
                else a[NoSuchElementException] should be thrownBy buf.read()
              }
            case writeCount if writeCount > 0 ⇒
              writeCount times {
                val next = ints.next().toString
                if (buf.write(next)) queue.enqueue(next)
              }
            case 0 ⇒ // ignore
          }
      }
    }
  }
}
