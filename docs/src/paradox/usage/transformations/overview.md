Transformations Overview
========================

One core value of any streaming infrastructure lies in the breadth and quality of the stream transformations that are
available to users. They form the building blocks from which application-specific logic is constructed, and the more
blocks of all shapes, colors and sizes are readily available (to stay within the building analogy) the easier it is to
quickly and elegantly assemble something that fits the specific needs.


Transformation Categories
-------------------------

Since the number of pre-defined transformations is large (and continuously growing) it makes sense to think about how
they can be grouped and categorized. Here are a few dimensions by which stream transformations can be distinguished:

Shape
: The "shape" of a transformation is determined by only looking at the number of inputs and outputs. Simple
transformations have only one input and one output, fan-outs have one input and several outputs, fan-ins have several
inputs and one output, and so on.

"Regular" vs. Stream-of-Streams
: Transformations that work on streams-of-streams (e.g. `flattenConcat`) have a higher internal (and usage) complexity
than transformations for which the stream's data elements are opaque. This is because the state-space of the
state-machine implementing the transformation's logic increases significantly with the number of open streams that a
stage has to concurrently deal with. Therefore it makes sense to categorize them as a separate group.

Sync vs. Async
: Synchronous transformations can do all their work as direct reactions to signals from their peers. They don't have to
be "active" themselves. Asynchronous transformations however need to able to react to signals from outside the
streaming infrastructure, e.g. timers, completion of a future, network events, etc.</br/>
As such they cannot run synchronously on the caller thread.

Primary vs. Compound
: Some transformations feel more "basic" than others. For example, `drop(n)` might be regarded as a pretty low-level
transformation, whereas `slice(startIndex, length)`, which can be easily constructed from a `drop` followed by a `take`,
appears higher-level. However, one could also implement `drop` via `slice` if required.
Therefore the question, which of two transformations is more basic, cannot be decided by the semantics alone. One has to
look at the particular *implementation* in order to find out, which transformation has a **primary** implementation and
which might be a **compound** of other, more basic ones.

 
Documentation Entry-Points
--------------------------

Here are the entry points to the documentation of all transformations, organized by shape and splitting out
streams-of-streams:
 
* [Simple Transformations](simple.md)
* [Fan-outs](fan-outs.md)
* [Fan-Ins](fan-ins.md)
* [Creating Streams-of-Streams](injecting.md)
* [Flattening Streams-of-Streams](flattening.md)

If you'd like to get closer to the source, most transformations are defined in the `swave.core.StreamOps` trait:

* @scaladoc[StreamOps Scaladoc](swave.core.StreamOps) 
* @github[StreamOps Source Code](/core/src/main/scala/swave/core/StreamOps.scala)   
