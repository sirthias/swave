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

package org.scalacheck

import org.scalacheck.rng.Seed

import language.reflectiveCalls

import util.ConsoleReporter

/** Represents a collection of properties, with convenient methods
 *  for checking all properties at once. This class is itself a property, which
 *  holds if and only if all of the contained properties hold.
 *  <p>Properties are added in the following way:</p>
 *
 *  {{{
 *  object MyProps extends Properties("MyProps") {
 *    property("myProp1") = forAll { (n:Int, m:Int) =>
 *      n+m == m+n
 *    }
 *  }
 *  }}}
 */
class Properties(val name: String) {

  private val props = new scala.collection.mutable.ListBuffer[(String,Prop)]

  /**
   * Changes to the test parameters that are specific to this class.
   * Can be used to set custom parameter values for this test.
   */
  def overrideParameters(p: Test.Parameters): Test.Parameters = p

  /** Returns all properties of this collection in a list of name/property
   *  pairs.  */
  def properties: Seq[(String,Prop)] = props

  /** Convenience method that checks the properties with the given parameters
   *  (or default parameters, if not specified)
   *  and reports the result on the console. Should only be used when running
   *  tests interactively within the Scala REPL.
   *
   *  If you need to get the results
   *  from the test use the `check` methods in [[org.scalacheck.Test]]
   *  instead. */
  def check(prms: Test.Parameters = Test.Parameters.default): Unit = {
    val params = overrideParameters(prms)
    Test.checkProperties(
      params.withTestCallback(ConsoleReporter(1) chain params.testCallback), this
    )
  }

  /** Convenience method that makes it possible to use this property collection
   *  as an application that checks itself on execution. Calls `System.exit`
   *  with the exit code set to the number of failed properties. */
  def main(args: Array[String]): Unit =
    Test.cmdLineParser.parseParams(args) match {
      case (applyCmdParams, Nil) =>
        val params = applyCmdParams(overrideParameters(Test.Parameters.default))
        val res = Test.checkProperties(params, this)
        val numFailed = res.count(!_._2.passed)
        if (numFailed > 0) {
          println(s"Found $numFailed failing properties.")
          System.exit(1)
        } else {
          System.exit(0)
        }
      case (_, os) =>
        println(s"Incorrect options: $os")
        Test.cmdLineParser.printHelp
        System.exit(-1)
    }

  /** Adds all properties from another property collection to this one */
  def include(ps: Properties): Unit =
    include(ps, prefix = "")

  /** Adds all properties from another property collection to this one
   *  with a prefix this is prepended to each included property's name. */
  def include(ps: Properties, prefix: String): Unit =
    for((n,p) <- ps.properties) property(prefix + n) = p

  /** Used for specifying properties. Usage:
   *  {{{
   *  property("myProp") = ...
   *  }}}
   */
  sealed class PropertySpecifier() {
    def update(propName: String, p: => Prop) = {
      props += ((name+"."+propName, Prop.delay(p)))
    }
  }

  lazy val property = new PropertySpecifier()

  sealed class PropertyWithSeedSpecifier() {
    def update(propName: String, optSeed: Option[String], p: => Prop) = {
      val fullName = s"$name.$propName"
      optSeed match {
        case Some(encodedSeed) =>
          val seed = Seed.fromBase64(encodedSeed)
          props += ((fullName, Prop.delay(p).useSeed(fullName, seed)))
        case None =>
          props += ((fullName, Prop.delay(p).viewSeed(fullName)))
      }
    }
  }

  lazy val propertyWithSeed = new PropertyWithSeedSpecifier()
}
