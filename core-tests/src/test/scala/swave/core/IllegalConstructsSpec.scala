/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import org.scalatest.FreeSpec
import shapeless.test.illTyped

class IllegalConstructsSpec extends FreeSpec {

  "Illegal DSL constructs shouldn't compile" - {

    "illegal switch fanout continue with too few substreams" in {
      illTyped(
        """Stream(1, 2, 3)
           .switchIf(_ > 0)
           .sub.end
           .continue""",
        "Cannot continue stream definition here! You still have at least one unconsumed fan-out sub-stream.")
    }

    "illegal switch fanout subContinue with too few substreams" in {
      illTyped(
        """Stream(1, 2, 3)
           .switchIf(_ > 0)
           .subContinue""",
        "`subContinue` is only possible with exactly one remaining fan-out sub-stream unconsumed!")
    }

    "illegal switch fanout sub with too many substreams" in {
      illTyped(
        """Stream(1, 2, 3)
           .switchIf(_ > 0)
           .sub.end
           .sub.end
           .sub.end""",
        "Illegal substream definition! All available fan-out sub-streams have already been consumed.")
    }

    "illegal fanout continue with zero unterminated fanin sub-streams" in {
      illTyped(
        """Stream(1, 2, 3)
           .fanOutBroadcast()
           .continue""",
        "Continuation is only possible with exactly one open fan-in sub-stream!")
    }

    "illegal fanout continue with two unterminated fanin sub-streams" in {
      illTyped(
        """Stream(1, 2, 3)
           .fanOutBroadcast()
           .sub.end
           .sub.end
           .continue""",
        "Continuation is only possible with exactly one open fan-in sub-stream!")
    }

    "illegal zero sub fanin" in {
      illTyped(
        """Stream(1, 2, 3)
           .fanOutBroadcast()
           .fanInConcat""",
        "Cannot fan-in here. You need to have at least two open fan-in sub-streams.")
    }
  }
}
