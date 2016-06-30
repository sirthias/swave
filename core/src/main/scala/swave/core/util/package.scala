/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import com.typesafe.config.Config
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.collection.mutable
import shapeless.HList

package object util {

  private[this] val _identityFunc = (x: Any) ⇒ x
  def identityFunc[T]: T ⇒ T = _identityFunc.asInstanceOf[T ⇒ T]

  def identityHash(obj: AnyRef): String = Integer.toHexString(System.identityHashCode(obj))

  val dropFunc: Any ⇒ Unit = _ ⇒ ()

  val oneIntFunc: Any ⇒ Int = _ ⇒ 1

  def isPowerOf2(i: Int): Boolean = Integer.lowestOneBit(i) == i

  def roundUpToNextPowerOf2(i: Int): Int = 1 << (32 - Integer.numberOfLeadingZeros(i - 1))

  implicit def richByteArray(array: Array[Byte]): RichByteArray = new RichByteArray(array)
  implicit def richConfig[T](config: Config): RichConfig = new RichConfig(config)
  implicit def richDuration(duration: Duration): RichDuration = new RichDuration(duration)
  implicit def richFiniteDuration(duration: FiniteDuration): RichFiniteDuration = new RichFiniteDuration(duration)
  implicit def richFuture[T](future: Future[T]): RichFuture[T] = new RichFuture(future)
  implicit def richHList[L <: HList](list: L): RichHList[L] = new RichHList(list)
  implicit def richInt(int: Int): RichInt = new RichInt(int)
  implicit def richList[T](list: List[T]): RichList[T] = new RichList(list)
  implicit def richLong(long: Long): RichLong = new RichLong(long)
  implicit def richArrayBuffer[T](seq: mutable.ArrayBuffer[T]): RichArrayBuffer[T] = new RichArrayBuffer(seq)
  implicit def richRefArray[T <: AnyRef](array: Array[T]): RichRefArray[T] = new RichRefArray(array)
  implicit def richSeq[T](seq: Seq[T]): RichSeq[T] = new RichSeq(seq)
  implicit def richString(string: String): RichString = new RichString(string)
  implicit def richTraversable[T](seq: Traversable[T]): RichTraversable[T] = new RichTraversable(seq)
}
