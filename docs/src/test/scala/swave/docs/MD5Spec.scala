/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import java.nio.file.Files
import java.nio.charset.StandardCharsets._
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}
import scala.concurrent.duration._
import swave.core.util._

class MD5Spec extends FreeSpec with Matchers with BeforeAndAfterAll {

  val testFileContent = "swave rocks!"

  val testFile = {
    val f = Files.createTempFile("md5-spec", ".tmp")
    Files.newBufferedWriter(f, UTF_8).append(testFileContent).close()
    f
  }

  "the examples in the `MD5` chapter should work as expected" - {

    "example" in {
      //#example
      import java.security.MessageDigest
      import java.io.File
      import scala.concurrent.Future
      import swave.core.io.files._   // enables `Spout.fromFile`
      import swave.compat.scodec._   // enables `ByteVector` support
      import swave.core._

      implicit val env = StreamEnv()

      def md5sum(file: File): Future[String] = {
        val md5 = MessageDigest.getInstance("MD5")
        Spout.fromFile(file)                                      // Spout[ByteVector]
          .fold(md5) { (m, bytes) => m.update(bytes.toArray); m } // Spout[MessageDigest]
          .flatMap(_.digest().iterator)                           // Spout[Byte]
          .map(_ & 0xFF)                                          // Spout[Int]
          .map("%02x" format _)                                   // Spout[String]
          .drainToMkString(limit = 32)                            // Future[String]
      }

      // don't forget to shutdown the StreamEnv at application exit with
      // env.shutdown()
      //#example

      try md5sum(testFile.toFile).await(2.seconds) shouldEqual "e1b2b603f9cca4a909c07d42a5788fe3"
      finally env.shutdown()
    }
  }

  override protected def afterAll(): Unit = Files.delete(testFile)
}
