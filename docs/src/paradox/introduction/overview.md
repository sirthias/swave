Overview
========

What is *swave*?
----------------

*swave* is a library providing a general, @ref[Reactive-Streams](reactive-streams.md)-compliant streaming infrastructure for applications
written in [Scala]. As such it plays in the same space as @extref[Akka-Stream], but differs from it in some important
aspects. (For more info on this check out @ref[swave vs Akka-Stream](swave-vs-akka-stream.md).)

           
Motivation
----------

The requirements imposed on today's software are manifold. Apart from simply "working" the applications we write are
supposed to be responsive, resilient and elastic (as defined, for example, by the [Reactive Manifesto]).
Additionally they are expected to digest ever-growing data volumes and make efficient use of their increasingly parallel
and distributed hardware basis.
All this presents significant challenges, which fuel the continuing search for programming models that are attractive
and advantageous in today computing environments.

One such programming model, although hardly a recent invention at its core, is [Stream Processing].

[Scala] provides the @scaladoc[scala.immutable.Stream](scala.immutable.Stream) datatype with its standard library
but this abstraction is quite limited in its functionality and barely scratches the surface of the benefits that
[Stream Processing] as a programming abstraction is able to provide.
Therefore a number of stream processing libraries have recently emerged in the [Scala] eco-system, the most prominent
of which are probably @extref[Akka-Stream] and [FS2] (formally [Scalaz Stream]), which approach the field from two
different angles.

Since modern stream processing, especially with support for non-blocking backpressure, is both incredibly sexy
and still relatively new, with plenty of learnings still to be unearthed, we believe that there is more than enough
space for a few more approaches to be tried and tested before settling on one or two "standard implementations".
    
*swave* attempts to be just that: A stream processing implementation, purely in and for [Scala], which further explores
the boundaries of the possible, sometimes by being intentionally different.
 
 
Design Principles
-----------------

*swave's* design is guided by the following principles:

High-Performance
: *swave's* internal mechanics as carefully crafted for excellent performance in high-load environments. Apart from
  being fully non-blocking this also entails a focus on limiting allocations (and thereby GC pressure).  
  The type-heavy DSL is designed to add almost no runtime overhead.
  
Lightweight
: All dependencies are very carefully managed, *swave's* codebase itself is kept as lean as possible.

Conceptually Simple
: Being effective at writing stream-based logic can take some getting used to. *swave* tries to make this as easy as
  possible by only introducing concepts that are absolutely essential.  

Debuggable
: As with any new programming model you'll likely spent the large majority of your time debugging code that doesn't
  (yet) quite do what you expect it to. Making this task as easy as possible is one of *swave's* key focus points.

Type-Safe and Concise
: Being written in and for [Scala] a type-safe API is naturally key. *swave* tries to let the compiler statically verify
  as much of your program logic as possible while reducing necessary boilerplate to the absolute minimum. 

  [Scala]: http://www.scala-lang.org/
  [Akka-Stream]: akka:stream/index
  [Reactive Manifesto]: http://www.reactivemanifesto.org/
  [Stream Processing]: https://en.wikipedia.org/wiki/Stream_processing 
  [FS2]: https://github.com/functional-streams-for-scala/fs2
  [Scalaz Stream]: https://github.com/scalaz/scalaz-stream