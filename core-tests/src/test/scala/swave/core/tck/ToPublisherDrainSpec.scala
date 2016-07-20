/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.tck

import org.reactivestreams.Publisher
import swave.core._

class ToPublisherDrainSpec(ignore: Any) // disabled by default, remove parameter to enable the test
    extends SwavePublisherVerification[Int] {

  implicit val env = StreamEnv()

  def createPublisher(elements: Long): Publisher[Int] =
    Spout.from(0).take(elements).drainTo(Drain.toPublisher()).get
}
