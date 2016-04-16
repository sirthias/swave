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

final class AnyRefExtractor[A, B <: AnyRef](f: A ⇒ B) {
  def unapply(value: A): AnyRefExtractor.Extraction[B] = new AnyRefExtractor.Extraction(f(value))
}

object AnyRefExtractor {
  class Extraction[T <: AnyRef](val extraction: T) extends AnyVal {
    def isEmpty: Boolean = extraction eq null
    def get: T = extraction
  }

  def apply[A, B <: AnyRef](f: A ⇒ B): AnyRefExtractor[A, B] = new AnyRefExtractor(f)
}
