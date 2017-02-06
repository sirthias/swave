/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.hash

import scodec.bits.ByteVector
import swave.compat.scodec._
import swave.core._

class HashTransformationSpec extends SwaveSpec {
  import swave.core.text._

  implicit val env = StreamEnv()

  "HashTransformations" - {

    "md5" in {
      Spout
        .one("swave rocks!")
        .utf8Encode
        .md5
        .drainToHead()
        .value
        .get
        .get shouldEqual ByteVector.fromHex("e1b2b603f9cca4a909c07d42a5788fe3").get
    }
  }
}
