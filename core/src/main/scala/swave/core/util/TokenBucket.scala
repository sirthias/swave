/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

/**
  * The code in this file is only slightly adapted from a previous version, which
  * is licensed under the Apache License 2.0 and bears this copyright notice:
  *
  *     Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
  *
  * So most credit for this work should go to the original authors.
  */
package swave.core.util

import swave.core.macros._

private[util] abstract class TokenBucket(capacity: Long, nanosBetweenTokens: Long) {
  requireArg(capacity >= 0, "`capacity` must be >= 0")
  requireArg(nanosBetweenTokens > 0, "`nanosBetweenTokens` must be > 0")

  private[this] var availableTokens: Long = _
  private[this] var lastUpdate: Long      = _

  protected def currentTime: Long

  /**
    * Initializes the bucket to configured capacity and current time.
    */
  def reset(): Unit = {
    availableTokens = capacity
    lastUpdate = currentTime
  }

  /**
    * This method is called whenever an element should be passed through the token-bucket.
    * Returns the number of ns the element needs to be delayed to conform with the token bucket parameters.
    * Returns zero if the element can be emitted immediately.
    * The method does not handle overflow, i.e. if an element is to be delayed longer in ns
    * than what can be represented as a positive Long then an undefined value is returned.
    *
    * If a non-zero value is returned it's the responsibility of the caller to not call this method again
    * before the returned delay has been elapsed (it may be called later though).
    * This class does not check or protect against early calls.
    *
    * @param cost the token cost of the current element. Can be larger than the capacity of the bucket.
    * @return the number of ns the current element need to be delayed for
    */
  def offer(cost: Long): Long = {
    requireArg(cost >= 0, "`cost` must be >= 0")

    val now         = currentTime
    val timeElapsed = now - lastUpdate

    val tokensArrived =
      if (timeElapsed >= nanosBetweenTokens) {
        if (timeElapsed < nanosBetweenTokens * 2) {
          // only one tick elapsed since the last call
          lastUpdate += nanosBetweenTokens
          1L
        } else { // more than a single tick, we need to fall back to the somewhat slow integer division
          val tokensArrived = timeElapsed / nanosBetweenTokens
          lastUpdate += tokensArrived * nanosBetweenTokens
          tokensArrived
        }
      } else 0L // there was no tick since the last call

    availableTokens = math.min(availableTokens + tokensArrived, capacity)

    if (cost <= availableTokens) {
      availableTokens -= cost
      0L
    } else {
      val remainingCost = cost - availableTokens
      // tokens always arrive at exact multiples of the token generation period, we must account for that
      val timeSinceTokenArrival = now - lastUpdate
      val delay                 = remainingCost * nanosBetweenTokens - timeSinceTokenArrival
      availableTokens = 0
      lastUpdate = now + delay
      delay
    }
  }

}

/**
  * Default implementation of [[TokenBucket]] that uses `System.nanoTime` as the time source.
  */
final class NanoTimeTokenBucket(_cap: Long, _period: Long) extends TokenBucket(_cap, _period) {
  def currentTime = System.nanoTime()
}
