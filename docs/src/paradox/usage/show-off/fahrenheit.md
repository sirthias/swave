Fahrenheit Example
==================

This example is a direct translation of the one shown in the [FS2 README].<br/>
It demonstrates a simple stream pipeline that makes use of the builtin @ref[File IO] and @ref[Text Handling] support to
perform a "practical" task:

@@snip [-]($test/FahrenheitSpec.scala) { #example }

This code constructs a @scaladoc[StreamGraph](swave.core.StreamGraph), which reads bytes from an input file (in chunks
of @ref[configurable] size), UTF8-decodes them and re-chunks into lines. If then filters out the lines containing the
actual data (i.e. skips blank lines and comment lines), parses the temperatur readings in degrees Fahrenheit, converts
them to Celsius, re-inserts newlines, UTF-8 encodes the output, and writes the resulting bytes to the output file.

All of this happens in constant memory and, because `Spout.fromFile` and `Drain.toFile` come with implicit asynchronous
boundaries, potentially parallelized across three cores. (The explanations in from the @ref[MD5 Example] also apply
here, with one more asynchronous boundary before the `Drain.toFile`.) 

The input and output files will be always and automatically be properly closed, upon normal termination as well as in
the case of errors.

Since the stream graph has three asynchronous regions it might be that parts of the graph are still running when
the result future is completed. Therefore we should not shut down the @scaladoc[StreamEnv] immediately afterwards
but hook into the `run.termination` @scaladoc[Future] to trigger a graceful shutdown.

  [FS2 README]: https://github.com/functional-streams-for-scala/fs2
  [File IO]: ../domain/file-io.md
  [Text Handling]: ../domain/text.md
  [configurable]: ../further/configuration.md
  [MD5 Example]: md5.md
  [StreamEnv]: swave.core.StreamEnv
  [Future]: scala.concurrent.Future