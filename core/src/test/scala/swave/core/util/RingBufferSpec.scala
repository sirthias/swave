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

package swave.core.util

import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{ FreeSpec, Matchers }

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
        buf ← bufferGen
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
        buf ← bufferGen
        opCount ← Gen.choose(5, 20)
        ops ← Gen.listOfN(opCount, Gen.choose(-10, 20))
      } yield (buf, ops)

      forAll(gen) {
        case (buf, ops) ⇒
          val queue = collection.mutable.Queue[String]()
          val ints = Iterator.from(0)
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
