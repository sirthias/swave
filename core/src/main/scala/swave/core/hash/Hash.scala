/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.hash

import java.security.MessageDigest
import swave.core.Pipe
import swave.core.io.Bytes

object Hash extends HashTransformations

trait HashTransformations {

  final def md2[T: Bytes]: Pipe[T, T]    = digest(MessageDigest.getInstance("MD2"))
  final def md5[T: Bytes]: Pipe[T, T]    = digest(MessageDigest.getInstance("MD5"))
  final def sha1[T: Bytes]: Pipe[T, T]   = digest(MessageDigest.getInstance("SHA-1"))
  final def sha256[T: Bytes]: Pipe[T, T] = digest(MessageDigest.getInstance("SHA-256"))
  final def sha384[T: Bytes]: Pipe[T, T] = digest(MessageDigest.getInstance("SHA-384"))
  final def sha512[T: Bytes]: Pipe[T, T] = digest(MessageDigest.getInstance("SHA-512"))

  final def digest[T](md: MessageDigest)(implicit ev: Bytes[T]): Pipe[T, T] =
    Pipe[T]
      .fold(md) {
        new ((MessageDigest, T) => MessageDigest) {
          implicit def decorator(value: T): Bytes.Decorator[T] = Bytes.decorator(value)
          private[this] var array: Array[Byte]                 = _

          def apply(md: MessageDigest, bytes: T): MessageDigest = {
            val size =
              bytes.size match {
                case x if x > Int.MaxValue => sys.error("Cannot hash chunks with more than `Int.MaxValue` bytes")
                case x                     => x.toInt
              }
            if ((array eq null) || array.length < size) array = new Array[Byte](size)
            bytes.copyToArray(array, 0)
            md.update(array, 0, size)
            md
          }
        }
      }
      .map(md => ev(md.digest())) named "digest"
}
