/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.tck

import org.reactivestreams.Processor
import swave.core.{Pipe, StreamEnv}

class IdentityProcessorSpec(ignore: Any) // disabled by default, remove parameter to enable the test
    extends SwaveIdentityProcessorVerification[Int] {

  implicit val env = StreamEnv()

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] =
    Pipe[Int].toProcessor.run().get.result.get

  override def createElement(element: Int): Int = element

}
