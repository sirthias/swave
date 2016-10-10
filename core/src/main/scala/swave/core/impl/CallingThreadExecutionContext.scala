/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

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
