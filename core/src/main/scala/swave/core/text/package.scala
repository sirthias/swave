/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import java.nio.charset.{Charset, CodingErrorAction}
import swave.core.io.Bytes

package object text {

  implicit class RichBytesStreamOpsText[T, S[X] <: StreamOps[X]](val underlying: S[T]) extends AnyVal {

    def decode(charset: Charset,
               onMalformedInput: CodingErrorAction = CodingErrorAction.REPORT,
               onUnmappableCharacter: CodingErrorAction = CodingErrorAction.REPLACE)
              (implicit ev: Bytes[T]): S[T]#Repr[String] =
      underlying.via(Text.decode[T](charset, onMalformedInput, onUnmappableCharacter))

    def utf8Decode(implicit ev: Bytes[T]): S[T]#Repr[String] =
      underlying.via(Text.utf8Decode)
  }

  implicit class RichStringStreamOpsText[S <: StreamOps[String]](val underlying: S) extends AnyVal {

    def encode[T :Bytes](charset: Charset): S#Repr[T] =
      underlying.via(Text.encode(charset))

    def utf8Encode[T :Bytes]: S#Repr[T] =
      underlying.via(Text.utf8Encode)

    def lines: S#Repr[String] =
      underlying.via(Text.lines)
  }
}
