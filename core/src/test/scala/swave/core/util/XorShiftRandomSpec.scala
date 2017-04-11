/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.util

import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FreeSpec, Matchers}

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
      val array  = Array("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")
      val array2 = java.util.Arrays.copyOf(array, array.length)
      random.shuffle_!(array2)
      array2 should not equal array // will fail once every approx. 10! = 3.628.800 test runs
      array2.sorted shouldEqual array
    }
  }
}
