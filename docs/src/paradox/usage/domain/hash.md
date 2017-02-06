Hashing Support
===============

When working with raw bytes in a stream-based fashion, e.g. when @ref[reading data from a file], a common task is
computing hashes across some or all parts of a stream of raw bytes.
*swave* comes with efficient built-in support for the most important hash algorithms. 
 
 
Usage
-----

The easiest way to get access to the pre-defined hashing transformations is this import:

```scala
import swave.core.hash._
```

as well as *one* of these imports (see the @ref[section on the `Bytes` type class][bytes] for more details):

```scala
import swave.compat.akka.byteStringBytes // for packaging raw bytes as an `akka.util.ByteString`
import swave.compat.scodec.byteVectorBytes // for packaging raw bytes as an `scodec.bits.ByteVector`
import swave.core.io.byteArrayBytes // for packaging raw bytes as a plain `Array[Byte]` 
```

With this in place you have the following two additional transformations available on streams of `ByteString`,
`ByteVector` or `Array[Byte]` (depending on what import you chose above):

```scala
byteStream.md2 // transforms into a stream of 16 bytes
byteStream.md5 // transforms into a stream of 16 bytes
byteStream.sha1 // transforms into a stream of 20 bytes
byteStream.sha256 // transforms into a stream of 32 bytes 
byteStream.sha384 // transforms into a stream of 48 bytes
byteStream.sha512 // transforms into a stream of 64 bytes
byteStream.digest(md: MessageDigest) // transforms into a stream of n bytes 
```

All of these transformations turn a stream of an arbitrary number of bytes into a stream of a well-defined number of
bytes, which represent the hash value.

If you prefer, all of these transformations are also defines as @ref[Pipes] in the
@scaladoc[swave.core.hash.HashTransformations] trait and the @scaladoc[swave.core.hash.Hash] object.
 

  [reading data from a file]: file-io.md
  [bytes]: file-io.md#the-bytes-t-type-class
  [Pipes]: ../further/pipes.md
  [swave.core.hash.HashTransformations]: swave.core.hash.HashTransformations
  [swave.core.hash.Hash]: swave.core.hash.Hash$