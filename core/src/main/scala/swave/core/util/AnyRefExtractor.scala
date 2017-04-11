/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.util

/**
  * Enables zero-overhead custom extractors for extractions that are reference types, e.g.
  * {{{
  *   scala> case class Person(age: Int, name: String)
  *   defined class Person
  *
  *   scala> val Child = AnyRefExtractor[Person, Person](person => if (person.age < 18) person else null)
  *   Child: AnyRefExtractor[Person,Person] = AnyRefExtractor@1e592ef2
  *
  *   scala> val Child(x) = Person(9, "Alice")
  *   x: Person = Person(9,Bob)
  *
  *   scala> val Child(x) = Person(21, "Bob")
  *   scala.MatchError: Person(21,Alice) (of class Person)
  *   ... 29 elided
  * }}}
  *
  * @param f the function performing the extraction; must return the extraction or null if no extraction is available
  * @tparam A the type to extract from
  * @tparam B the type of the extraction (must be a reference type)
  */
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
