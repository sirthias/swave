/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

/*

Copyright (c) 2007-2016, Rickard Nilsson

All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright notice,
      this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright notice,
      this list of conditions and the following disclaimer in the documentation
      and/or other materials provided with the distribution.
    * Neither the name of the EPFL nor the names of its contributors
      may be used to endorse or promote products derived from this software
      without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.scalacheck.util

sealed trait FreqMap[T] extends Serializable {
  protected val underlying: scala.collection.immutable.Map[T,Int]
  val total: Int

  def +(t: T): FreqMap[T] = new FreqMap[T] {
    private val n = FreqMap.this.underlying.get(t) match {
      case None => 1
      case Some(n) => n+1
    }
    val underlying = FreqMap.this.underlying + (t -> n)
    val total = FreqMap.this.total + 1
  }

  def -(t: T): FreqMap[T] = new FreqMap[T] {
    val underlying = FreqMap.this.underlying.get(t) match {
      case None => FreqMap.this.underlying
      case Some(n) => FreqMap.this.underlying + (t -> (n-1))
    }
    val total = FreqMap.this.total + 1
  }

  def ++(fm: FreqMap[T]): FreqMap[T] = new FreqMap[T] {
    private val keys = FreqMap.this.underlying.keySet ++ fm.underlying.keySet
    private val mappings = keys.toStream.map { x =>
      (x, fm.getCount(x).getOrElse(0) + FreqMap.this.getCount(x).getOrElse(0))
    }
    val underlying = scala.collection.immutable.Map(mappings: _*)
    val total = FreqMap.this.total + fm.total
  }

  def --(fm: FreqMap[T]): FreqMap[T] = new FreqMap[T] {
    val underlying = FreqMap.this.underlying transform {
      case (x,n) => n - fm.getCount(x).getOrElse(0)
    }
    lazy val total = (0 /: underlying.valuesIterator) (_ + _)
  }

  def getCount(t: T) = underlying.get(t)

  def getCounts: List[(T,Int)] = underlying.toList.sortBy(-_._2)

  def getRatio(t: T) = for(c <- getCount(t)) yield c.toDouble/total

  def getRatios = for((t,c) <- getCounts) yield (t, c.toDouble/total)

  override def toString = underlying.toString
}

object FreqMap {
  def empty[T]: FreqMap[T] = new FreqMap[T] {
    val underlying = scala.collection.immutable.Map.empty[T,Int]
    val total = 0
  }
}
