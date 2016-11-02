*swave* vs. Akka-Stream
=======================

While *swave* and [Akka-Stream] are more closely related than, for example, *swave* and [FS2] they still differ
in some important aspects.
Apart from the underlying implementations, which are completely unalike, the key differences are:

Scala-only vs. Scala + Java
: *swave* is Scala-only, whereas [Akka-Stream] comes with a Scala API *and* a Java API. While the latter addresses
  a strictly larger user base it also adds a significant amount of development and maintenance effort.

Non-Lifted vs. Lifted Design
: All of *swave*'s key types are non-lifted, single use abstractions. This differs from [Akka-Stream] which tries to
  keep its abstraction as reusable as possible (which is not always possible).
  
Sync + Async vs. Async only
: In addition to scheduling tasks onto configurable thread-pools *swave* allows for synchronous
  (but still non-blocking!) execution on the caller thread, if the stream setup permits it.
  [Akka-Stream] always runs its streams on independent Akka Actors (and thus thread-pools).
  
Shapeless-based DSL vs. Point-to-point Graph DSL
: While *swave* tries to keep single operations named similarly to [Akka-Stream] the DSL for creating more complex
  stream setups, with fan-outs, fan-ins, loops, etc. is substantially different between the two.
  
Quick Evolution vs. Long-term stability
: Being part of [Akka] Akka-Stream has much tighter requirements on long-term stability and binary compatibility than
  *swave*. This means that you can probably rely on [Akka-Stream] as a more stable and mature foundation going forward.
  However, the less stringent stability requirements allow us to evolve *swave* faster and incorporate new learnings
  more freely.

Non-blocking, back-pressured stream processing in Scala is an exciting and promising arena, with most of its potential
still untapped. Therefore we don't see *swave* as a head-on competitor to [Akka-Stream], trying to steal away from its
user base. Rather we regards ourselves as two teams that are exploring this still new field together, learning from one
another and comparing approaches on numerous levels.
The more users whose needs we can jointly address the more the Scala eco-system will benefit as a whole.

  [Akka-Stream]: http://doc.akka.io/docs/akka/2.4/scala/stream/index.html
  [FS2]: https://github.com/functional-streams-for-scala/fs2
  [Akka]: http://akka.io/
 