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
           .fanOut()
           .continue""",
        "Continuation is only possible with exactly one open fan-in sub-stream!")
    }

    "illegal fanout continue with two unterminated fanin sub-streams" in {
      illTyped(
        """Stream(1, 2, 3)
           .fanOut()
           .sub.end
           .sub.end
           .continue""",
        "Continuation is only possible with exactly one open fan-in sub-stream!")
    }

    "illegal zero sub fanin" in {
      illTyped(
        """Stream(1, 2, 3)
           .fanOut()
           .fanInConcat""",
        "Cannot fan-in here. You need to have at least two open fan-in sub-streams.")
    }
  }
}
