/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

/**
  * The [[RuntimeException]] thrown by the `limit` operation.
  */
final case class StreamLimitExceeded(max: Long, offendingElem: Any)
    extends RuntimeException(s"Limit of $max exceeded by element '$offendingElem'")

final class ConfigurationException(msg: String) extends RuntimeException(msg)

final class IllegalAsyncBoundaryException(msg: String) extends RuntimeException(msg)

final class IllegalReuseException(msg: String, cause: Throwable = null) extends RuntimeException(msg, cause)

final class UnclosedStreamGraphException(msg: String, cause: Throwable) extends RuntimeException(msg, cause)

final class SubscriptionTimeoutException(msg: String) extends RuntimeException(msg)

final class StreamTimeoutException(msg: String) extends RuntimeException(msg)

final class UnsupportedSecondSubscriptionException extends RuntimeException

/**
  * The exception instance thrown by the [[swave.core.Buffer.OverflowStrategy.Fail]]
  * when the buffer is full and a new element arrives.
  */
final class BufferOverflowFailure(elem: Any)
    extends RuntimeException(s"Element `$elem` arrived but there was no more space to buffer it")

/**
  * The Exception that is thrown when a synchronous stream stops running without having been properly terminated.
  */
final class UnterminatedSynchronousStreamException
    extends RuntimeException("The synchronous stream stopped running without having been properly terminated.")
