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

import swave.core._

private[core] final class DispatchersImpl private (
    val settings: Dispatchers.Settings,
    val defaultDispatcher: Dispatcher,
    dispatcherMap: Map[Symbol, DispatcherImpl]) extends Dispatchers {

  def apply(id: Symbol) = dispatcherMap(id)

  /**
   * Triggers a shutdown and returns a function that
   * returns the ids of all dispatchers that are not yet terminated.
   */
  def shutdownAll(): () ⇒ List[Symbol] = {
    val map = dispatcherMap.mapValues(_.shutdown())
    () ⇒ map.collect({ case (id, isTerminated) if !isTerminated() ⇒ id })(collection.breakOut)
  }

}

private[core] object DispatchersImpl {
  def apply(settings: Dispatchers.Settings): DispatchersImpl = {
    val dispatcherMap =
      settings.dispatcherDefs
        .mapValues(DispatcherImpl(_))
        .withDefault(id ⇒ sys.error(s"Dispatcher '$id is not defined"))
    new DispatchersImpl(settings, dispatcherMap('default), dispatcherMap)
  }
}