File IO
=======

*swave* comes with built-in support for stream-based file IO.


Usage
-----

You enable built-in support for stream-based file IO with this single import: 

```scala
import swave.core.io.files._
```

This gives you the following three additional methods on the `Spout` companion object, which create @ref[Spout]
instances for reading arbitrarily large files from the file system in a stream-based fashion (signatures slightly
simplified):

```scala
Spout.fromFileName[T: Bytes](fileName: String): Spout[T]
Spout.fromFile[T: Bytes](file: File): Spout[T]
Spout.fromPath[T: Bytes](path: Path): Spout[T]
```

The import also gives you the following three additional methods on the `Drain` companion object, which create
@ref[Drain] instances for writing arbitrarily large files to the file system in a stream-based fashion (signatures
slightly simplified): 

```scala
Drain.toFileName[T: Bytes](fileName: String): Drain[T, Future[Long]]
Drain.toFile[T: Bytes](file: File): Drain[T, Future[Long]]
Drain.toPath[T: Bytes](path: Path): Drain[T, Future[Long]]
```

Apart from one thing these signatures are probably quite self-explanatory. Still, you might want to take a look at the
@scaladoc[ScalaDoc of the `SpoutFromFiles` trait](swave.core.io.files.SpoutFromFiles) and the
@scaladoc[ScalaDoc of the `DrainToFiles` trait](swave.core.io.files.DrainToFiles) for more details.
Probably also interesting are the
@github[sources of the `SpoutFromFiles` trait](/core/src/main/scala/swave/core/io/files/SpoutFromFiles.scala) and the
@github[sources of the `DrainToFiles` trait](/core/src/main/scala/swave/core/io/files/DrainToFiles.scala).
 
The one thing that deserves more explanation here is the type parameter with the `Bytes` context bound.


The `Bytes[T]` Type Class
-------------------------
  
As you can see all of the methods shown above take a type parameter `T`, which (if not explicitly specified) will be
inferred from the (single) context bound instance of the type class `Bytes[T]` that is in scope at the call site.

The purpose of this type parameter is to allow *swave* to abstract over the concrete data type that represents a chunk
of raw bytes, which all lower-level IO operations typically work with. *swave*'s approach here is not to duplicate the
excellent work that other projects have invested in this area, but leave the decision of which type to use to the user.

Currently *swave* pre-defines three `Bytes[T]` implementations, for these types:

1. `akka.util.ByteString` (as part of the @ref[swave-akka-compat] module)

    Enabled with `import swave.compat.akka.byteStringBytes`.

2. `scodec.bits.ByteVector` (as part of the @ref[swave-scodec-compat] module)

    Enabled with `import swave.compat.scodec.byteVectorBytes`.

3. `Array[Byte]` (as part of the `core` module)

    Enabled with `import swave.core.io.byteArrayBytes`.

*swave* intentionally doesn't pick any of these as a "preferred default", so you'll have to make a conscious choice as
to what you'd like to use. If you have no clear preference we'd recommend you rely on @scaladoc[scodec.bits.ByteVector]
as it appears to be the most flexible implementation with the broadest API offering.
 
If you rely on the `byteArrayBytes` implementation (which has the advantage of not requiring any module dependency in
addition to `swave-core`) be aware that *swave* treats instances of `Array[Byte]` as immutable, even though they this is
not and cannot be enforced. So, if you violate this convension in your own code by mutating byte array instances in
place you should be sure to know what you are doing.


  [Spout]: ../spouts.md
  [Drain]: ../drains.md
  [swave-akka-compat]: ../swave-akka-compat/index.md
  [swave-scodec-compat]: ../swave-scodec-compat/index.md
  [scodec.bits.ByteVector]: scodec.bits.ByteVector