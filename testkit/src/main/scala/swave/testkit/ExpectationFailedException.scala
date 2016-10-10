/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.testkit

final class ExpectationFailedException(msg: String) extends RuntimeException(msg)

object ExpectationFailedException {
  def apply(received: Option[Any], expected: Option[Any]): ExpectationFailedException = {
    val msg = (received, expected) match {
      case (Some(r), Some(e)) ⇒ s"Received `$r` but expected `$e`"
      case (None, Some(e))    ⇒ s"Received nothing but expected `$e`"
      case (Some(r), None)    ⇒ s"Received `$r` when no signal was expected"
      case (None, None)       ⇒ throw new IllegalStateException
    }
    new ExpectationFailedException("Test expectation failed: " + msg)
  }

  def apply(msg: String): ExpectationFailedException = new ExpectationFailedException(msg)
}
