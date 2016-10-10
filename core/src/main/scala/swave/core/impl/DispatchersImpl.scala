/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl

import swave.core._

private[core] final class DispatchersImpl private (val settings: Dispatchers.Settings,
                                                   val defaultDispatcher: Dispatcher,
                                                   dispatcherMap: Map[String, DispatcherImpl])
    extends Dispatchers {

  def apply(id: String) = dispatcherMap(id)

  /**
    * Triggers a shutdown and returns a function that
    * returns the ids of all dispatchers that are not yet terminated.
    */
  def shutdownAll(): () ⇒ List[String] = {
    val map = dispatcherMap.transform((_, dispatcher) ⇒ dispatcher.shutdown())
    () ⇒
      map.collect({ case (id, isTerminated) if !isTerminated() ⇒ id })(collection.breakOut)
  }

}

private[core] object DispatchersImpl {
  def apply(settings: Dispatchers.Settings): DispatchersImpl = {
    val dispatcherMap =
      settings.dispatcherDefs
        .transform((_, settings) ⇒ DispatcherImpl(settings))
        .withDefault(id ⇒ sys.error(s"Dispatcher '$id' is not defined"))
    new DispatchersImpl(settings, dispatcherMap("default"), dispatcherMap)
  }
}
