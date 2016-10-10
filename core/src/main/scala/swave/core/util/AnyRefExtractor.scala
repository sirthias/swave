/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.util

final class AnyRefExtractor[A, B <: AnyRef](f: A ⇒ B) {
  def unapply(value: A): AnyRefExtractor.Extraction[B] = new AnyRefExtractor.Extraction(f(value))
}

object AnyRefExtractor {
  class Extraction[T <: AnyRef](val extraction: T) extends AnyVal {
    def isEmpty: Boolean = extraction eq null
    def get: T           = extraction
  }

  def apply[A, B <: AnyRef](f: A ⇒ B): AnyRefExtractor[A, B] = new AnyRefExtractor(f)
}
