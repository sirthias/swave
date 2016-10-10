/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import org.scalatest.prop.Checkers
import swave.core.SwaveSpec
import swave.testkit.TestGeneration

abstract class SyncPipeSpec extends SwaveSpec with Checkers with TestGeneration
