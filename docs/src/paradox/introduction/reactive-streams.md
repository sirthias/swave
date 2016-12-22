Reactive Streams
================

[Reactive Streams][Reactive Streams] (RS) is an initiative to provide a standard for asynchronous stream processing with
non-blocking backpressure. At its core it defines a **protocol** for moving streaming data across an asynchronous
boundary (e.g. between threads or machines) in a way that allows both sides to run independently, in their own time and
space, within bounded memory (no unlimited buffering) and without any blocking.

Like many other protocols this RS protocol is not something that developers of higher-level applications typically
implement themselves. (HTTP, for example, is another protocol that you don't want to implement yourself only to be able
to use it.) Rather one normally relies on a specific implementation (e.g. in the form of a library) which provides
higher-level interfaces idiomatic to the respective (language) environment.

*swave* is such an implementation of the RS protocol for [Scala].


The "org.reactivestreams" % "reactive-streams" Artifact
-------------------------------------------------------

One output of the [Reactive Streams] initiative is a set of Java interfaces that are provided to the public as a Java
library under the [Creative Commons Zero] Public Domain license (group-id `org.reactivestreams`,
artifact-id `reactive-streams`).
The interface types defined in this library, most importantly
@scaladoc[org.reactivestreams.Publisher](org.reactivestreams.Publisher) and
@scaladoc[org.reactivestreams.Subscriber](org.reactivestreams.Publisher), are implemented by RS-compatible
libraries and thus allow for seamless integration between different RS-implementations on the JVM.

Normally you shouldn't have to define the `reactive-streams` JAR as an explicit dependency of your project.
It'll automatically land on your classpath as a transitive dependency of *swave* (or @extref[Akka-Stream], for example).


RS Support in *swave*
---------------------

*swave* allows you to interface with other RS-implementations in any way you want.

You can:

- Provide and consume @scaladoc[RS Publishers](org.reactivestreams.Publisher)
- Provide and feed into @scaladoc[RS Subscribers](org.reactivestreams.Subscriber)
- Provide and use @scaladoc[RS Processors](org.reactivestreams.Processor)

Check the respective documentation on @ref[Spouts](../usage/spouts.md), @ref[Drains](../usage/drains.md) and
@ref[Pipes](../usage/further/pipes.md) for more details. 

  [Reactive Streams]: http://www.reactive-streams.org/
  [Scala]: http://www.scala-lang.org/
  [Creative Commons Zero]: http://creativecommons.org/publicdomain/zero/1.0
  [Akka-Stream]: akka:stream/index
  *[RS]: Reactive Streams
