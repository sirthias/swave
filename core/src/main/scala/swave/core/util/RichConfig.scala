/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.util

import com.typesafe.config.{Config, ConfigException}
import swave.core.ConfigurationException
import scala.concurrent.duration._

final class RichConfig(val underlying: Config) extends AnyVal {

  def getScalaDuration(path: String): Duration =
    underlying.getString(path) match {
      case "infinite" ⇒ Duration.Inf
      case x          ⇒ Duration(x)
    }

  def getFiniteDuration(path: String): FiniteDuration =
    Duration(underlying getString path) match {
      case x: FiniteDuration ⇒ x
      case _                 ⇒ throw new ConfigurationException(s"Config setting '$path' must be a finite duration")
    }

  def getPossiblyInfiniteInt(path: String): Int =
    underlying.getString(path) match {
      case "infinite" ⇒ Int.MaxValue
      case x          ⇒ underlying getInt path
    }

  def getIntBytes(path: String): Int = {
    val value: Long = underlying getBytes path
    if (value <= Int.MaxValue) value.toInt
    else throw new ConfigurationException(s"Config setting '$path' must not be larger than ${Int.MaxValue}")
  }

  def getPossiblyInfiniteIntBytes(path: String): Int =
    underlying.getString(path) match {
      case "infinite" ⇒ Int.MaxValue
      case x          ⇒ getIntBytes(path)
    }

  def getPossiblyInfiniteLongBytes(path: String): Long =
    underlying.getString(path) match {
      case "infinite" ⇒ Long.MaxValue
      case x          ⇒ underlying getBytes path
    }

  def getOptionalConfig(path: String): Option[Config] =
    try Some(underlying getConfig path)
    catch {
      case _: ConfigException.Missing ⇒ None
    }
}
