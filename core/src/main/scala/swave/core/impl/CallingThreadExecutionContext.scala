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

package swave.core.impl

import scala.concurrent.ExecutionContext

/**
 * [[ExecutionContext]] which runs everything on the calling thread.
 * Only use it for non-blocking and non-throwing tasks!
 */
private[swave] object CallingThreadExecutionContext extends ExecutionContext {

  def execute(runnable: Runnable): Unit = runnable.run()

  override def reportFailure(t: Throwable): Unit =
    throw new IllegalStateException("Exception in CallingThreadExecutionContext", t)
}
