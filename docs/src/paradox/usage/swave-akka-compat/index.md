swave-akka-compat
=================

The `swave-akka-compat` modules provides for very easy interconnectivity between *swave* and @extref[Akka-Stream].


Installation
------------

See the @ref[Setup] chapter for details on how to pull `swave-akka-compat` into your build.


Usage
-----

Once you have `swave-akka-compat` on your classpath this import: 

```scala
import swave.compat.akka._
```

gives you six "decorators" that allow for almost seamless conversion of all major stream graph components between both
worlds. You can convert *swave* components into *akka* ones like this:

```scala
val akkaSource = spout.toAkkaSource
val akkaFlow = pipe.toAkkaFlow
val akkaSink = drain.toAkkaSink
```

as well as the other way around:

```scala
val spout = akkaSource.toSpout
val pipe = akkaFlow.toPipe 
val drain = akkaSink.toDrain
```

Additionally the import brings a `Bytes[T]` type class instance in scope that allows @ref[File IO] to seamlessly work
with `akka.util.ByteString` as the main vehicle for raw bytes. See the section on the @ref[Bytes] type class for more
info on this. 


API Docs (ScalaDoc)
-------------------

The ScalaDoc for *swave-scodec-compat* can be found @scaladoc[>>> HERE <<<](swave.compat.akka.index).


  [Akka-Stream]: akka:stream/index
  [Setup]: ../setup.md
  [File IO]: ../domain/file-io.md
  [Bytes]: ../domain/file-io.md#the-bytes-t-type-class