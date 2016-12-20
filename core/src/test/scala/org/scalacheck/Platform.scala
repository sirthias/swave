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

import org.scalacheck.Test._

private[scalacheck] object Platform {

  import util.FreqMap

  def runWorkers(
    params: Parameters,
    workerFun: Int => Result,
    stop: () => Unit
  ): Result = {
    import params._

    def mergeResults(r1: Result, r2: Result): Result = {
      val Result(st1, s1, d1, fm1, _) = r1
      val Result(st2, s2, d2, fm2, _) = r2
      if (st1 != Passed && st1 != Exhausted)
        Result(st1, s1+s2, d1+d2, fm1++fm2, 0)
      else if (st2 != Passed && st2 != Exhausted)
        Result(st2, s1+s2, d1+d2, fm1++fm2, 0)
      else {
        if (s1+s2 >= minSuccessfulTests && maxDiscardRatio*(s1+s2) >= (d1+d2))
          Result(Passed, s1+s2, d1+d2, fm1++fm2, 0)
        else
          Result(Exhausted, s1+s2, d1+d2, fm1++fm2, 0)
      }
    }

    if(workers < 2) workerFun(0)
    else {
      import concurrent._
      val tp = java.util.concurrent.Executors.newFixedThreadPool(workers)
      implicit val ec = ExecutionContext.fromExecutor(tp)
      try {
        val fs = List.range(0,workers) map (idx => Future {
          params.customClassLoader.map(
            Thread.currentThread.setContextClassLoader(_)
          )
          blocking { workerFun(idx) }
        })
        val zeroRes = Result(Passed,0,0,FreqMap.empty[Set[Any]],0)
        val res =
          if (fs.isEmpty) Future.successful(zeroRes)
          else Future.sequence(fs).map(_.foldLeft(zeroRes)(mergeResults))
        Await.result(res, concurrent.duration.Duration.Inf)
      } finally {
        stop()
        tp.shutdown()
      }
    }
  }

  def newInstance(name: String, loader: ClassLoader)(args: Seq[AnyRef]): AnyRef =
    if(!args.isEmpty) ???
    else Class.forName(name, true, loader).newInstance.asInstanceOf[AnyRef]

  def loadModule(name: String, loader: ClassLoader): AnyRef =
    Class.forName(name + "$", true, loader).getField("MODULE$").get(null)

  class JSExportDescendentObjects(ignoreInvalidDescendants: Boolean)
      extends scala.annotation.Annotation {
    def this() = this(false)
  }

  class JSExportDescendentClasses(ignoreInvalidDescendants: Boolean)
      extends scala.annotation.Annotation {
    def this() = this(false)
  }
}
