swave-scodec-compat
===================

The `swave-scodec-compat` modules provides for very easy interconnectivity between *swave* and [scodec].

Currently all it contains is an implementation of the `Bytes[T]` type class for @scaladoc[scodec.bits.ByteVector].
(See @ref[this section](../domain/file-io.md#the-bytes-t-type-class) for details.)
 
You enable `Bytes` support for @scaladoc[scodec.bits.ByteVector] with this import
 
```scala
import swave.compat.scodec.byteVectorBytes
```


API Docs (ScalaDoc)
-------------------

The ScalaDoc for *swave-scodec-compat* can be found @scaladoc[>>> HERE <<<](swave.compat.scodec.index).


  [scodec]: http://scodec.org/
  [scodec.bits.ByteVector]: scodec.bits.ByteVector