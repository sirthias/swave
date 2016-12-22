/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.docs

import java.nio.charset.StandardCharsets._
import java.nio.file.Files
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}

class FahrenheitSpec extends FreeSpec with Matchers with BeforeAndAfterAll {

  val testFileContent =
    """// Temperature Readings in Fahrenheit
      |50.8
      |80.2
      |63.0
      |-14
      |
      |// Especially important temperatures
      |32
      |-459.67
      |451
    """.stripMargin

  val inputFile = {
    val path = Files.createTempFile("fahrenheit-spec-input", ".tmp")
    Files.newBufferedWriter(path, UTF_8).append(testFileContent).close()
    path.toFile
  }
  val outputFile = Files.createTempFile("fahrenheit-spec-output", ".tmp").toFile

  "the examples in the `Fahrenheit` chapter should work as expected" - {

    "example" in {
      def println(s: String) = () // disable the printing below

      //#example
      import java.io.File
      import scala.util.{Failure, Success}
      import scala.concurrent.Future
      import swave.core.io.files._   // enables `Spout.fromFile`
      import swave.compat.scodec._   // enables `ByteVector` support
      import swave.core.text._       // enables text transformations
      import swave.core._

      implicit val env = StreamEnv()
      import env.defaultDispatcher // for the future transformations below

      def fahrenheitToCelsius(f: Double): Double =
        (f - 32.0) * (5.0/9.0)

      def converter(fahrenheitReadingsInput: File,
                    celciusReadingsOutput: File): RunnableStreamGraph[Future[Long]] =
        Spout.fromFile(fahrenheitReadingsInput)    // Spout[ByteVector]
          .utf8Decode                              // Spout[String]
          .lines                                   // Spout[String]
          .filterNot(_.trim.isEmpty)               // Spout[String]
          .filterNot(_ startsWith "//")            // Spout[String]
          .map(_.toDouble)                         // Spout[Double]
          .map(fahrenheitToCelsius)                // Spout[Double]
          .map("%.2f" format _)                    // Spout[String]
          .intersperse("\n")                       // Spout[String]
          .utf8Encode                              // Spout[ByteVector]
          .to(Drain.toFile(celciusReadingsOutput)) // StreamGraph[Future[Long]]
          .seal()                                  // RunnableStreamGraph[Future[Long]]

      // when we are ready to roll, start the stream
      val run: StreamRun[Future[Long]] =
        converter(inputFile, outputFile).run()

      // since the stream runs asynchronously we can't directly access the result
      run.result.onComplete {
        case Success(x) => println(s"OK, $x bytes written")
        case Failure(e) => println(s"Error: $e")
      }

      // shut down when everything has terminated
      env.shutdownOn(run.termination)
      //#example

      import swave.core.util._
      run.termination.await()
      FileIO.readFile(outputFile).decodeUtf8 shouldEqual Right {
        """10.44
          |26.78
          |17.22
          |-25.56
          |0.00
          |-273.15
          |232.78""".stripMargin
      }
    }
  }

  override protected def afterAll(): Unit = Files.delete(inputFile.toPath)
}
