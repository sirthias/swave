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

package swave.core.impl.stages

import swave.core.PipeElem
import swave.core.impl.{ Inport, Outport, StartContext, PipeElemImpl }

private[stages] abstract class Stage0 extends PipeElemImpl { this: PipeElem.Basic ⇒

  protected final def unexpectedStart(ctx: StartContext): Nothing =
    illegalState(s"Unexpected start() in $this")

  protected final def unexpectedSubscribe(out: Outport): Nothing =
    illegalState(s"Unexpected subscribe() from out '$out' in $this")

  protected final def unexpectedRequestInt(n: Int, out: Outport): Nothing = unexpectedRequest(n.toLong, out)
  protected final def unexpectedRequest(n: Long, out: Outport): Nothing =
    illegalState(s"Unexpected request($n) from out '$out' in $this")

  protected final def unexpectedCancel(out: Outport): Nothing =
    illegalState(s"Unexpected cancel() from out '$out' in $this")

  protected final def unexpectedOnSubscribe(in: Inport): Nothing =
    illegalState(s"Unexpected onSubscribe() from in '$in' in $this")

  protected final def unexpectedOnNext(elem: Any, in: Inport): Nothing =
    illegalState(s"Unexpected onNext($elem) from in '$in' in $this")

  protected final def unexpectedOnComplete(in: Inport): Nothing =
    illegalState(s"Unexpected onComplete() from in '$in' in $this")

  protected final def unexpectedOnError(error: Throwable, in: Inport): Nothing =
    illegalState(s"Unexpected onError($error) from in '$in' in $this")

  protected final def unexpectedExtra: Stage.Extra = {
    case ev ⇒ illegalState(s"Unexpected extra($ev) in $this")
  }

  protected final def doubleSubscribe(out: Outport): Nothing =
    illegalState(s"Illegal double subscribe from out '$out' in $this")

  protected final def doubleOnSubscribe(in: Inport): Nothing =
    illegalState(s"Illegal double onSubscribe from in '$in' in $this")

  protected final def illegalState(msg: String) = throw new IllegalStateException(msg)

}
