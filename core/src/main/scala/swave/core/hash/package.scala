/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import java.security.MessageDigest
import swave.core.io.Bytes

package object hash {

  implicit class RichBytesStreamOpsHash[T, S[X] <: StreamOps[X]](val underlying: S[T]) extends AnyVal {

    def md2(implicit ev: Bytes[T]): S[T]#Repr[T]    = underlying.via(Hash.md2[T])
    def md5(implicit ev: Bytes[T]): S[T]#Repr[T]    = underlying.via(Hash.md5[T])
    def sha1(implicit ev: Bytes[T]): S[T]#Repr[T]   = underlying.via(Hash.sha1[T])
    def sha256(implicit ev: Bytes[T]): S[T]#Repr[T] = underlying.via(Hash.sha256[T])
    def sha384(implicit ev: Bytes[T]): S[T]#Repr[T] = underlying.via(Hash.sha384[T])
    def sha512(implicit ev: Bytes[T]): S[T]#Repr[T] = underlying.via(Hash.sha512[T])

    def digest(md: MessageDigest)(implicit ev: Bytes[T]): S[T]#Repr[T] =
      underlying.via(Hash.digest(md))
  }

}
