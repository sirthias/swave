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
