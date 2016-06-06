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

package swave.core

import com.typesafe.config.Config
import scala.concurrent.Future
import scala.collection.mutable
import shapeless.HList
import swave.core.macros.HelperMacros

package object util extends HelperMacros {

  private[this] val _identityFunc = (x: Any) ⇒ x
  def identityFunc[T]: T ⇒ T = _identityFunc.asInstanceOf[T ⇒ T]

  def identityHash(obj: AnyRef): String = Integer.toHexString(System.identityHashCode(obj))

  private[this] val _dropFunc = (_: Any) ⇒ ()
  def dropFunc[T]: T ⇒ Unit = _dropFunc.asInstanceOf[T ⇒ Unit]

  def isPowerOf2(i: Int): Boolean = Integer.lowestOneBit(i) == i

  def roundUpToNextPowerOf2(i: Int): Int = 1 << (32 - Integer.numberOfLeadingZeros(i - 1))

  implicit def richByteArray(array: Array[Byte]): RichByteArray = new RichByteArray(array)
  implicit def richConfig[T](config: Config): RichConfig = new RichConfig(config)
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
