Quick Start
===========

Like most other Scala streaming toolkits *swave* fully separates stream construction and stream execution into two
distinct phases. First, you define your stream setup using a concise and flexible DSL. During this phase no resources
are being claimed (apart from the memory for the graph structures) and no data start to flow.
Only when everything is fully assembled and you explicitly start the stream will the internal machinery actually spring
to life.

But let's start from the beginning...


Required Imports
----------------

After having added *swave* to your build (as described in the @ref[Setup] chapter) you bring all the key
identifiers into scope with this single import:

@@snip [-]($test/QuickStartSpec.scala) { #core-import }

In most cases this is all you need, but sometimes additional imports are required in order to enable certain support
functionality (like the @ref[Domain Adapters]).


Creating Spouts
---------------

With the main import in place we can create sources of stream elements, which are called @ref[Spouts] in
*swave*.<br/>
("spout" is an English word for a tube or lip projecting from a container, through which liquid can be poured.)

Here is a simple one:

@@snip [-]($test/QuickStartSpec.scala) { #spout }

As you can probably guess a `Spout[T]` is parameterized with the type of the elements that the spout produces.
*swave* predefines quite a few ways to create spouts from other types, e.g. from
@scaladoc[Iterable](scala.collection.immutable.Iterable),
@scaladoc[Iterators](scala.collection.Iterator),
@scaladoc[Option](scala.Option) and @scaladoc[Try](scala.util.Try), but also
@scaladoc[Future] or @scaladoc[Publisher](org.reactivestreams.Publisher).
Check out the chapter on @ref[Spouts] for more details.

Attaching Transformations
-------------------------

Once you have a `Spout` you can define @ref[transformations] on it, e.g. like this:
  
@@snip [-]($test/QuickStartSpec.scala) { #spout-simple-ops }

Most @ref[transformations] give you back another `Spout` of the same or a different type, but some, especially the
@ref[fan-outs] and @ref[fan-ins] work a bit differently.<br/>
The predefined @ref[transformations] represent the real "meat" of *swave*. They encode a lot of the general logic that
is typically required when working with streams. The power of stream-processing stems in large parts from being able to
nicely assemble higher-level logic from lower-level primitives in a concise and elegant fashion. Therefore a central
part of learning to program with streams is understanding which transformations already exist and how a given piece of
business logic might be encoded by combining them in the right way.
As with most programs there are usually many ways to achieve the same thing.<br/>
For example, here are ten ways of producing the same stream (the first 100 natural numbers) with *swave*:

@@snip [-]($test/QuickStartSpec.scala) { #ten-spouts }

Attaching Drains
----------------

After you've finished defining transformations you'll want to run your stream, i.e. have it produce its output.<br/>
In order to do that, however, we first have to define what should happen with the stream output, i.e. where it should
be produced *to*. This is done by attaching a @ref[Drain] to the spout.

A @ref[Drain] consumes the data elements of a stream for the purpose of producing some *result*.
For example, this result could be a @scaladoc[Seq](scala.collection.immutable.Seq) of all elements, just the first or
just the last element, or no element at all. In the latter case the drain might merely serve to achieve some kind of
side-effect, like the execution of a function for each element produced by the stream.

Here is a `Drain` that collects all incoming elements into a `Vector`:

@@snip [-]($test/QuickStartSpec.scala) { #seq-drain }

In addition to the type parameter for the element type (that we've already seen on `Spout`) a `Drain` has a second one,
which defines the type of the *result* that the drain produces. For most drains this will be a
@scaladoc[Future], since the `Drain` has to work with synchronous as well as asynchronous streams.

Here are some other frequently used drains:

@@snip [-]($test/QuickStartSpec.scala) { #more-drains }

As you can see, even the drains that produce "no" result, still produce one :).<br/>
For example the `Drain.foreach`, which runs a stream to completion only for side-effects, produces a `Future[Unit]`.
Even though in the happy case the future's value isn't very interesting (the `Unit` value), it still signals two things:

- if and when the stream completed (at the drain)
- whether it terminated successfully or with an error

Once you have a drain you can attach it to a matching (type-wise) `Spout` with `to(...)`, e.g.: 

@@snip [-]($test/QuickStartSpec.scala) { #streamGraph }  

The result of attaching a `Drain` to a `Spout` is a @scaladoc[StreamGraph], a type you'll probably use less frequently
in your own code. A `StreamGraph` represents a complete stream pipeline or graph setup, which is ready to be run.
Its single type parameter is the result type of the `Drain` that was used to "close" the stream pipeline.
 
Note that up until this point, including the attachment of a `Drain`, nothing has really happened apart from
*describing* what your stream setup looks like. No data has started to flow and no resources (apart from the memory for
the pipeline) have been claimed.


Running a Stream
----------------

It is only when you call `.run()` on a `StreamGraph` that the whole stream machinery kicks into motion and data elements
start to flow.
Thereby one very important thing to have in mind is that you can only ever call `.run()` **once** on any given
stream setup. After the first `.run()` call all elements of the stream setup, i.e. all involved spouts and drains
(as well as, potentially, @ref[Pipes] and @ref[Modules]) have been "spent". They cannot be used again and will cause all
subsequent streams that they are incorporated into to fail with an @scaladoc[IllegalReuseException].
If you want to re-run the stream another fresh `StreamGraph` instance must be created. 

*swave* streams can run synchronously (yet without any blocking!) purely on the caller thread. All the examples we've
looked at so far are of this kind. However, there might be components in your stream setup that require asynchronous
dispatch and therefore cannot run purely on the caller thread in a non-blocking fashion.
For example, a spout or drain might be connected to a network socket and thus must be "woken up" when new data arrive or
the kernel signals that the socket is now ready to accept data. Or the stream might contain a transformation that
requires the concept of "time" in order to do its thing (like @ref[throttle] or @ref[takeWithin]).<br/>
Or you might want to introduce an asynchronous boundary manually (see the @ref[asyncBoundary] transformation), in order
to allow for different parts of your pipeline to be run in parallel.
In all of these cases the stream will be started asynchronously when you call `.run()`, which means it will be started
and run on another thread.


StreamEnv
---------

When you try to run a stream now, only with what we've talked about so far, you'll see that there is still one final
thing missing. For example if we try to compile this snippet:

@@snip [-]($test/QuickStartSpec.scala) { #run }

the compiler would stop us with this error message:

```nohighlight
... : could not find implicit value for parameter env: swave.core.StreamEnv
        .run()
            ^  
```

which tells us that we need to supply an implicit @scaladoc[StreamEnv](swave.core.StreamEnv) instance.

The @scaladoc[StreamEnv](swave.core.StreamEnv) is similar to the @scaladoc[ActorSystem](akka.core.ActorSystem) type
in Akka. It provides all the global configuration information that's required by *swave's* internal streaming engine.
For example, the `StreamEnv` contains the thread-pool configuration(s) and general
@scaladoc[Settings](swave.core.StreamEnv.Settings) you have @ref[configured] as well as a global
`Logger` and @scaladoc[Scheduler](swave.core.Scheduler) instance.

The simplest and yet perfectly fine way to supply a `StreamEnv` instance is this:

@@snip [-]($test/QuickStartSpec.scala) { #env }

This will simply load the complete @ref[Configuration] from the `reference.conf` and, potentially,
`application.conf` files on your classpath.

Note: If any of the streams that is started with a particular `StreamEnv` instance is asynchronous, i.e. requires
dispatch onto another thread, the `StreamEnv` instance needs to be explicitly shut down when the application wants to
exit. Otherwise the internal thread-pools will not be terminated and thus keep the JVM from exiting (unless all
thread-pools are configured with `daemonic = on`, which is not the default).
 
This is how you trigger an orderly shutdown of a `StreamEnv` instance:

@@snip [-]($test/QuickStartSpec.scala) { #env-shutdown }


drainTo Shortcuts
-----------------

Since attaching a `Drain` and immediately calling `.run()` on the result is such a common pattern, *swave* offers
several shortcuts that allow you to do both in one single step. For example:

@@snip [-]($test/QuickStartSpec.scala) { #shortcuts }

There are more `drain...` variants available on `Spout`, you might want to
@scaladoc[check them out as](swave.core.Spout) well.


  [Setup]: setup.md
  [Domain Adapters]: domain/index.md
  [Spouts]: spouts.md
  [Drain]: drains.md
  [transformations]: transformations/overview.md
  [fan-outs]: transformations/fan-outs.md
  [fan-ins]: transformations/fan-ins.md
  [Pipes]: further/pipes.md
  [Modules]: further/modules.md
  [IllegalReuseException]: swave.core.IllegalReuseException
  [configured]: further/configuration.md
  [Configuration]: further/configuration.md
  [Future]: scala.concurrent.Future
  [StreamGraph]: swave.core.StreamGraph
  [throttle]: transformations/reference/throttle.md
  [takeWithin]: transformations/reference/takeWithin.md
  [asyncBoundary]: transformations/reference/asyncBoundary.md