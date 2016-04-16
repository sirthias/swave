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

class XorShiftRandomSpec extends FreeSpec with Matchers with GeneratorDrivenPropertyChecks {

  "XorShiftRandom" - {

    "nextLong" in {
      val random = XorShiftRandom()
      forAll(Gen.posNum[Long]) { bound ⇒
        random.nextLong(bound) should (be >= 0L and be < bound)
      }
    }

    "nextInt" in {
      val random = XorShiftRandom()
      forAll(Gen.posNum[Int]) { bound ⇒
        random.nextInt(bound) should (be >= 0 and be < bound)
      }
    }

    "nextDouble" in {
      val random = XorShiftRandom()
      forAll { (_: Unit) ⇒
        random.nextDouble() should (be >= 0.0 and be < 1.0)
      }
    }

    "shuffle" in {
      val random = XorShiftRandom()
      val array = Array("1", "2", "3", "4", "5", "6", "7", "8")
      val array2 = array.drop(0)
      random.shuffle_!(array2)
      array2 should not equal array
      array2.sorted shouldEqual array
    }
  }
}
