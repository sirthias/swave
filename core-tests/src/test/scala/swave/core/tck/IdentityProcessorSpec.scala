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

package swave.core.tck

import org.reactivestreams.Processor
import swave.core.{ StreamEnv, Pipe }

class IdentityProcessorSpec(ignore: Any) // disabled by default, remove parameter to enable the test
    extends SwaveIdentityProcessorVerification[Int] {

  implicit val env = StreamEnv()

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] =
    Pipe[Int].toProcessor.run().get

  override def createElement(element: Int): Int = element

}
