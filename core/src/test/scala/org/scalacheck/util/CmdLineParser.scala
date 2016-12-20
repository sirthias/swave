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

import scala.collection.Set

private[scalacheck] trait CmdLineParser {

  trait Opt[+T] {
    val default: T
    val names: Set[String]
    val help: String
  }
  trait Flag extends Opt[Unit]
  trait IntOpt extends Opt[Int]
  trait FloatOpt extends Opt[Float]
  trait StrOpt extends Opt[String]
  trait OpStrOpt extends Opt[Option[String]]

  class OptMap(private val opts: Map[Opt[_],Any] = Map.empty) {
    def apply(flag: Flag): Boolean = opts.contains(flag)
    def apply[T](opt: Opt[T]): T = opts.get(opt) match {
      case None => opt.default
      case Some(v) => v.asInstanceOf[T]
    }
    def set[T](o: (Opt[T], T)) = new OptMap(opts + o)
  }

  val opts: Set[Opt[_]]

  private def getOpt(s: String) = {
    if(s == null || s.length == 0 || s.charAt(0) != '-') None
    else opts.find(_.names.contains(s.drop(1)))
  }

  private def getStr(s: String) = Some(s)

  private def getInt(s: String) =
    if (s != null && s.length > 0 && s.forall(_.isDigit)) Some(s.toInt)
    else None

  private def getFloat(s: String) =
    if (s != null && s.matches("[0987654321]+\\.?[0987654321]*")) Some(s.toFloat)
    else None

  def printHelp() = {
    println("Available options:")
    opts.foreach { opt =>
      println("  " + opt.names.map("-"+_).mkString(", ") + ": " + opt.help)
    }
  }

  /** Parses a command line and returns a tuple of the parsed options,
   *  and any unrecognized strings */
  def parseArgs[T](args: Array[String]): (OptMap, List[String]) = {

    def parse(
      as: List[String], om: OptMap, us: List[String]
    ): (OptMap, List[String]) = as match {
      case Nil => (om, us)
      case a::Nil => getOpt(a) match {
        case Some(o: Flag) => parse(Nil, om.set((o,())), us)
        case _ => (om, us :+ a)
      }
      case a1::a2::as => (getOpt(a1) match {
        case Some(o: IntOpt) => getInt(a2).map(v => parse(as, om.set(o -> v), us))
        case Some(o: FloatOpt) => getFloat(a2).map(v => parse(as, om.set(o -> v), us))
        case Some(o: StrOpt) => getStr(a2).map(v => parse(as, om.set(o -> v), us))
        case Some(o: OpStrOpt) => getStr(a2).map(v => parse(as, om.set(o -> Option(v)), us))
        case _ => None
      }).getOrElse(parse(a2::as, om, us :+ a1))
    }

    parse(args.toList, new OptMap(), Nil)
  }
}
