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

class RichRefArraySpec extends FreeSpec with Matchers with GeneratorDrivenPropertyChecks {

  "RichRefArray" - {
    val stringArrays = Gen.containerOf[Array, String](Gen.alphaStr)

    "fastIndexOf" in {
      val arrayWithIndex =
        for {
          arr ← stringArrays
          ix ← Gen.chooseNum(0, arr.length + 1)
        } yield arr.map(Symbol(_)) → ix

      forAll(arrayWithIndex) {
        case (array, ix) ⇒
          val specimen = if (ix < array.length) array(ix) else 'foo
          array.fastIndexOf(specimen) shouldEqual array.indexOf(specimen)
      }
    }

    "reverse_!" in {
      forAll(stringArrays) { array ⇒
        val array2 = array.drop(0)
        array2.reverse_!()
        array2 shouldEqual array.reverse
      }
    }
  }
}
