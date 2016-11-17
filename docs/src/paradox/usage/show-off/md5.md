MD5 Example
===========

This example demonstrates a small practical application of file streaming in *swave*.<br/>
The following code shows how one could compute an MD5 hash string for files of arbitrary size:
 
@@snip [-]($test/MD5Spec.scala) { #example }

The stream graph defined by this code is quite straight-forward (a simple pipeline), but there are still a few
interesting points to discuss:

1. The @ref[Spout] produced by `Spout.fromFile` is always @ref[asynchronous] and runs on the dispatcher @ref[configured]
under the name "blocking-io" (see the @ref[File IO] chapter for more details). The reason for this is that there is no
general and truly asynchronous kernel API for non-blocking file IO, which causes all file system accesses to be
inherently blocking. In order to mitigate the potential resource exhaustion problems that could occur in higher load
scenarios all file IO operations in *swave* are fenced off from the rest of the application by confining them to the
"blocking-io" dispatcher.

2. *swave* itself doesn't provide an abstraction for an immutable sequence of bytes, because there already exist
excellent implementations for this, for example in [Akka] or [scodec]. Rather, *swave* defines a type class
@github[Bytes], which abstracts over available "byte sequence" implementations and also allows you to supply your own,
if required. *swave* comes with support for @scaladoc[akka.util.ByteString] and @scaladoc[scodec.bits.ByteVector]
predefined, which can be activated with a single import (see @ref[swave-akka-compat] and @ref[swave-scodec-compat] for
more details).
   
3. While the code above will work with files of arbitrary size the memory required during execution is "small" and fully
bounded. All action happens in chunks of @ref[configurable] size.<br/>
(see setting `swave.core.file-io.default-file-reading-chunk-size`)
 
4. Because the `fromFile` @ref[Spout] is asynchronous the stream pipeline in this example actually consists of two parts
that are running in parallel.

The last point deserves some further explanations.<br/>
Here is a visualization of the example's stream pipeline:

@@@ p { .centered }
![MD5 Example Stream Graph](.../md5-graph.svg)
@@@

As you can see there is an asynchronous boundary between the first two stages of the graph, which causes both parts
to run independently and concurrently from each other. Adding additional asynchronous boundaries, for increasing the
degree of concurrency, is as easy as adding an @ref[.async()] transformation at arbitrary points in the pipeline.

This makes trying out different distribution patterns of your business logic over several threads and cores extremely
easy. Adding a boundary means adding a single line of code. Moving a boundary means moving a single line of code.
If you contrast that with the amount of work required for introducing, moving or removing asynchronous boundaries in
traditional thread-based applications or even actor-based ones some of the benefits of stream-based programming should
become apparent.

  
  [Spout]: ../spouts.md
  [asynchronous]: ../further/sync-vs-async.md
  [configured]: ../further/configuration.md
  [configurable]: ../further/configuration.md
  [File IO]: ../io/file-io.md
  [Akka]: http://akka.io
  [scodec]: http://scodec.io
  [Bytes]: /core/src/main/scala/swave/core/io/Bytes.scala
  [akka.util.ByteString]: akka.util.ByteString
  [scodec.bits.ByteVector]: scodec.bits.ByteVector
  [swave-akka-compat]: ../swave-akka-compat/index.md
  [swave-scodec-compat]: ../swave-scodec-compat/index.md
  [.async()]: ../transformations/reference/async.md