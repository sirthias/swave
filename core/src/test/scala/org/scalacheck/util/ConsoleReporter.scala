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

import Pretty.{Params, pretty, format}
import org.scalacheck.Test

/** A [[org.scalacheck.Test.TestCallback]] implementation that prints
 *  test results directly to the console. This is the callback used
 *  by ScalaCheck's command line test runner, and when you run [[org.scalacheck.Prop!.check:Unit*]]
 */
class ConsoleReporter(val verbosity: Int) extends Test.TestCallback {

  private val prettyPrms = Params(verbosity)

  override def onTestResult(name: String, res: Test.Result) = {
    if(verbosity > 0) {
      if(name == "") {
        val s = (if(res.passed) "+ " else "! ") + pretty(res, prettyPrms)
        printf("\r%s\n", format(s, "", "", 75))
      } else {
        val s = (if(res.passed) "+ " else "! ") + name + ": " +
          pretty(res, prettyPrms)
        printf("\r%s\n", format(s, "", "", 75))
      }
    }
  }

}

object ConsoleReporter {

  /** Factory method, creates a ConsoleReporter with the
   *  the given verbosity */
  def apply(verbosity: Int = 0) = new ConsoleReporter(verbosity)

}
