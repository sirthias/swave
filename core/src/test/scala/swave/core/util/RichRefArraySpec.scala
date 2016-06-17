/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

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
