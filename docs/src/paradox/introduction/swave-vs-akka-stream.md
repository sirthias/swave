*swave* vs. Akka-Stream
=======================

While *swave* and @extref[Akka-Stream] are more closely related than, for example, *swave* and [FS2] they still differ
in some important aspects.
Apart from the underlying implementations, which are completely unalike, the key differences are:

Scala-only vs. Scala + Java
: *swave* is Scala-only, whereas @extref[Akka-Stream] comes with a Scala API *and* a Java API. While the latter
  addresses a strictly larger user base it also adds a significant amount of development and maintenance effort.

Non-Lifted vs. Lifted Design
: All of *swave*'s key types are non-lifted, single use abstractions. This differs from @extref[Akka-Stream] which tries
  to keep its abstraction as reusable as possible (which is not always possible).
  
Sync + Async vs. Async only
: In addition to scheduling tasks onto configurable thread-pools *swave* allows for synchronous
  (but still non-blocking!) execution on the caller thread, if the stream setup permits it.
  @extref[Akka-Stream] always runs its streams off the caller thread on independent Akka Actors (and thus thread-pools).
  
Fluent DSL vs. Point-to-point Graph DSL
: While *swave* tries to keep individual transformations named similarly to @extref[Akka-Stream] the DSL for creating
  more complex stream setups, with fan-outs, fan-ins, loops, etc. is substantially different between the two.
  
Full vs. Limited Stream-of-Streams Support
: *swave* fully supports streams of streams, where as Akka limits stream nesting to streams that handle all nested
  streams in the same way. The latter does have certain benefits (like inspectability of transformations on nested
  streams) but is strictly less powerful.
  
Performance
: In our latest benchmarks *swave* achieves between 1.3 and 4.8 times as much throughput as @extref[Akka-Stream] for
  quasi-identical streaming graphs, depending on the exact mix of transformations in the "hot" parts of the setup.
  
Quick Evolution vs. Long-term stability
: Being part of [Akka] Akka-Stream has much tighter requirements on long-term stability and binary compatibility than
  *swave*. This means that you can probably rely on @extref[Akka-Stream] as a more stable and mature foundation going
  forward. However, the less stringent stability requirements allow us to evolve *swave* faster and incorporate new
  learnings more freely.

Non-blocking, backpressured stream processing in Scala is an exciting and promising arena, with most of its potential
still untapped. Therefore we don't see *swave* as a head-on competitor to @extref[Akka-Stream], trying to steal away
from its user base. Rather we regards ourselves as two teams that are exploring this still new field together, learning
from one another and comparing approaches on numerous levels.
The more users whose needs we can jointly address the more the Scala eco-system will benefit as a whole.


Interfacing with Akka-Stream
----------------------------

Since seamless interaction between *swave* and @extref[Akka-Stream] is a frequent requirement *swave* has built-in
support for easy interfacing between the two. See the docs for the @ref[swave-akka-compat] module for more details.  


  [Akka-Stream]: akka:stream/index
  [FS2]: https://github.com/functional-streams-for-scala/fs2
  [Akka]: http://akka.io/
  [swave-akka-compat]: ../usage/swave-akka-compat/index.md 