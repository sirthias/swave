Spouts
======

A @scaladoc[Spout] is a @ref[stream graph component] with no input port and a single output port. As such it serves
as a "point of entry" into a stream setup for data elements from other sources (e.g. from memory, disk or the network).

@@@ div { .centered }
![A Spout](.../spout.svg)
@@@

*swave* predefines quite a few ways to create @scaladoc[Spouts] from other types and constructs.<br/>
Check out the @scaladoc[API docs of the Spout companion object](swave.core.Spout$) or its
@github[Source Code](/core/src/main/scala/swave/core/Spout.scala) for the complete reference.


Streamable[T]
-------------

*swave* defines a type-class @scaladoc[Streamable] which encapsulates the ability to create `Spout` instances from
values of type `T`:

@@snip [-]($core$/Streamable.scala) { #source-quote }

If an implicit `Streamable[T]` instance can be found (or created) for a type `T` then values of type `T`
can be turned into spouts with a simply `Spout(value)` call:

```scala
val foo: T = ...

// the element type of the spout is defined by the
// implicitly available `Streamable[T]` instance.
val fooSpout = Spout(foo)
```

Maybe more importantly, certain @ref[transformations] like `flatmap`, `flatConcat` or `flattenMerge` will then
automatically work on streams of type `T`.


### Predefined Streamables

*swave* @github[predefines] `Streamable` instances for these types:
 
- `Spout[T]` (spouts can be trivially turned into spouts)
- `Option[T]`
- `immutable.Iterable[T]`
- `Iterator[T]`
- `Publisher[T]`
- `Future[T]`
- `Try[T]`
- `T` for all `T :Bytes` (see the @ref[File IO] chapter for details)
- `() => T` for all `T: Streamable`

The following snippet, for example, shows, that flatmapping `Spout[Option[Int]]` to `Spout[Int]` works as expected
without further ado:

@@snip [-]($test$/SpoutSpec.scala) { #option-flatmap }


### Custom Streamable Example

You can easily make a custom type `T` fully "streamable" by defining the semantics for how instances of `T` can be
turned into a `Spout`. The way to do this is through a custom `Streamable[T]` implementation.
  
For example, suppose we'd like to be able to flatten streams of `Either[A, B]` instances into a stream of the most
specific common super-type of `A` and `B`. Here is how this could be done:

@@snip [-]($test$/SpoutSpec.scala) { #streamable-either }


  [Spout]: swave.core.Spout
  [Spouts]: swave.core.Spout
  [Streamable]: swave.core.Streamable
  [stream graph component]: basics.md#streams-as-graphs
  [transformations]: transformations/index.md
  [predefines]: /core/src/main/scala/swave/core/Streamable.scala
  [File IO]: domain/file-io.md