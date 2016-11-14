/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.testkit

import swave.core.{Extension, ExtensionId, StreamEnv}

object TestkitExtension extends ExtensionId[TestkitExtension] {
  def createExtension(env: StreamEnv) =
    new TestkitExtension(Testkit.Settings(env.config))
}

class TestkitExtension(val settings: Testkit.Settings) extends Extension
