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

sealed abstract class Split

object Split {
  sealed abstract class Command
  case object Drop extends Command // drop the current element
  case object Append extends Command // append to current sub-stream, if no sub-stream is currently open start a new one
  case object Last extends Command // append element (same as `Append`) and complete the current sub-stream afterwards
  case object First extends Command // complete the current sub-stream (if there is one) and start a new one with the current element
}

