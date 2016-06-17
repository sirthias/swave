/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

trait Cancellable {

  /**
   * Cancels this instance and returns true if that was successful,
   * i.e. if the instance was not already expired or cancelled.
   */
  def cancel(): Boolean

  /**
   * Returns false if this instance is not active (anymore).
   */
  def stillActive: Boolean

}

object Cancellable {

  val Inactive = new Cancellable {
    def cancel() = false
    def stillActive = false
  }
}
