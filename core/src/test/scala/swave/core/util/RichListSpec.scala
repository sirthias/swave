/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.util

import org.scalacheck.Gen
import org.scalatest.{ Matchers, FreeSpec }
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class RichListSpec extends FreeSpec with Matchers with GeneratorDrivenPropertyChecks {

  "RichList" - {

    "fastReverse" in {
      forAll { (list: List[Int]) ⇒
        list.fastReverse shouldEqual list.reverse
      }
    }

    "remove" in {
      forAll(Gen.choose(0, 5), Gen.choose(0, 4)) { (n: Int, x: Int) ⇒
        val list = List.tabulate(n)(identity)
        list.remove(x) shouldEqual list.filterNot(_ == x)
      }
    }
  }
}
