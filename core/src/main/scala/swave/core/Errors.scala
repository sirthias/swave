/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

final case class StreamLimitExceeded(max: Long, offendingElem: Any)
  extends RuntimeException(s"Limit of $max exceeded by element '$offendingElem'")

final class ConfigurationException(msg: String) extends RuntimeException(msg)

final class IllegalAsyncBoundaryException(msg: String) extends RuntimeException(msg)

final class IllegalReuseException(msg: String) extends RuntimeException(msg)

final class SubscriptionTimeoutException(msg: String) extends RuntimeException(msg)

final class UnsupportedSecondSubscriptionException extends RuntimeException
