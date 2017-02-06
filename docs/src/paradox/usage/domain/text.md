Text Transformations
====================

A very common need in stream-based applications is efficiently working with text content. The most frequently required
stream transformations are thereby:

1. Decoding text, i.e. converting a stream of bytes into a stream of characters with the help of some decoder for a
   particular charset, e.g. UTF-8.
    
2. Encoding text, i.e. converting a stream of characters into stream of bytes with the help of some encoder for a
   particular charset, e.g. UTF-8.
   
3. Line splitting, i.e. the "re-chunking" of an incoming stream of character groups (Strings) into a stream of lines,
   where each stream element corresponds to one line of text.

*swave* comes with efficient built-in support for these three main tasks.
 
 
Usage
-----

The easiest way to get access to the pre-defined text transformations is this import:

```scala
import swave.core.text._
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
byteStream.utf8Decode // transforms into a stream of strings
byteStream.decode(charset: Charset) // transforms into a stream of strings
```
 
as well as these tree additional transformations available on streams of `String`:
 
```scala
stringStream.utf8Encode // transforms into a stream of `ByteString`, `ByteVector` or `Array[Byte]`
stringStream.encode(charset: Charset) // transforms into a stream of strings
stringStream.lines // transforms into a stream of lines-as-strings 
```

If you prefer, all of these transformations are also defines as @ref[Pipes] in the
@scaladoc[swave.core.text.TextTransformations] trait and the @scaladoc[swave.core.text.Text] object.
 
 
  [bytes]: file-io.md#the-bytes-t-type-class
  [Pipes]: ../further/pipes.md
  [swave.core.text.TextTransformations]: swave.core.text.TextTransformations
  [swave.core.text.Text]: swave.core.text.Text$