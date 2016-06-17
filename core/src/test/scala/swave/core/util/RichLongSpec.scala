/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.util

import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{ FreeSpec, Matchers }

class RichLongSpec extends FreeSpec with Matchers with GeneratorDrivenPropertyChecks {

  "RichLong" - {
    val longMin = BigDecimal(Long.MinValue)
    val longMax = BigDecimal(Long.MaxValue)
    def bounded(d: BigDecimal) =
      if (d < longMin) Long.MinValue
      else if (d > longMax) Long.MaxValue
      else d.longValue()

    "⊹" in {
      forAll { (x: Long, y: Long) ⇒
        x ⊹ y shouldEqual bounded(BigDecimal(x) + BigDecimal(y))
      }
    }

    "×" in {
      forAll { (x: Long, y: Long) ⇒
        (x × y) shouldEqual bounded(BigDecimal(x) * BigDecimal(y))
      }
    }
  }
}
