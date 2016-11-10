Drains
======

A @scaladoc[Drain] is a @ref[stream graph component] with one input port and a no output port. As such it serves
as a "point of exit" from a stream setup to other destinations (e.g. to memory, disk or the network).

@@@ p { .centered }
![A Drain](.../drain.svg)
@@@

*swave* provides constructors for the most frequently used @scaladoc[Drains] on the @scaladoc[Drain companion object].
You can also look directly into @github[the sources](/core/src/main/scala/swave/core/Drain.scala) for the complete
reference.


Drain Result
------------

The type `Drain[-T, +R]` carries two type parameters. The first specifies the type of stream elements that the drain
is ready to receive and the second is the drain's "Result Type". A drain's *result* is the vehicle for getting data out
of a stream.

When a drain is used to close a stream graph, e.g. via the `to(...)` method of a @ref[Spout](spouts.md), the result type
is carried over to the @scaladoc[Piping] where it defines the type of the `run()` call, i.e. what you get back when the
stream is started.

For example:
 
@@snip [-]($test/DrainSpec.scala) { #examples }
 

Result Types are Async
----------------------

One consequence of the fact that every stage must be able to run asynchronously, off the caller thread, is that the
drain result (which is returned by the `run()` call on a @scaladoc[Piping]) cannot directly contain stream elements.
As blocking is not an option the `run()` call must return right away if the stream is running asynchronously,
often before the first data elements have even begun their traversal of the stream graph.<br/>

In consequence most kinds of drains have a result type of `Future[Something]` rather than just `Something`.

However, if you know that a stream runs synchronously (because it doesn't contain asynchronous stages) the `Future`
instance you'll get back from the `run()` call will be already fulfilled. This is because, in those cases, the
complete stream execution will happen "inside" the `run()` call. Then, and only then, it's fine to directly access the
future's value via `future.value.get` directly after the `run()` call.

  [stream graph component]: basics.md#streams-as-graphs
  [Piping]: swave.core.Piping
  [Drain]: swave.core.Drain
  [Drains]: swave.core.Drain
  [Drain companion object]: swave.core.Spout$
