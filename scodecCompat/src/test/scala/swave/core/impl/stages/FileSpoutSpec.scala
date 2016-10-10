/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages

import java.nio.charset.StandardCharsets._
import java.nio.file.Files
import scodec.bits.ByteVector
import swave.core._
import swave.testkit.Probes._

class FileSpoutSpec extends SwaveSpec {
  import swave.compat.scodec._
  import swave.core.io.files._

  implicit val env = StreamEnv()

  val TestText = {
    ("a" * 1000) +
      ("b" * 1000) +
      ("c" * 1000) +
      ("d" * 1000) +
      ("e" * 1000) +
      ("f" * 1000)
  }

  val testFile = {
    val f = Files.createTempFile("file-source-spec", ".tmp")
    Files.newBufferedWriter(f, UTF_8).append(TestText).close()
    f
  }

  val notExistingFile = {
    // this way we make sure it doesn't accidentally exist
    val f = Files.createTempFile("not-existing-file", ".tmp")
    Files.delete(f)
    f
  }

  override def afterTermination(): Unit = Files.delete(testFile)

  "Spout.fromPath must" - {

    "read contents from a file" in {
      val chunkSize = 512
      Spout
        .fromPath(testFile, chunkSize)
        .map(_.decodeUtf8.right.get)
        .drainTo(DrainProbe[String])
        .get
        .sendRequest(100)
        .expectNext(TestText.grouped(chunkSize).toList: _*)
        .expectComplete()
        .verifyCleanStop()
    }

    "produce an Error when the file doesn't exist" in {
      val drain = Spout.fromPath(notExistingFile).drainTo(DrainProbe[ByteVector]).get
      drain.expectError() shouldBe an[java.nio.file.NoSuchFileException]
      drain.verifyCleanStop()
    }
  }
}
