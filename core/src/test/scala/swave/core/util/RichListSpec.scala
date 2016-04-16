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
