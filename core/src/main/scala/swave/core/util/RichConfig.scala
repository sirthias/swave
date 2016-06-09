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

package swave.core.util

import com.typesafe.config.{ ConfigException, Config }
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
