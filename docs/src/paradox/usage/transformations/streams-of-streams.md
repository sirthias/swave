Streams-of-Streams
==================

Streams, like most other abstractions, can be nested. This means that you can have a `Spout[Spout[T]]` like you can
have a `List[List[T]]`. *swave* offers a number of @ref[transformations] that either

1. create a stream-of-streams from in incoming stream of "regular" elements (which we call **injecting**)

    @@@ p { .centered }
    ![An Injecting Transformation](.../injecting.svg)
    @@@ 

2. or flatten a stream-of-streams back to an outgoing stream of "regular" elements (which we call **flattening**)

    @@@ p { .centered }
    ![A Flattening Transformation](.../flattening.svg)
    @@@  

While the shape of these stream-of-streams transformations is the same as for @ref[Simple Transformations] the internal
and external complexity is significantly higher. This is because the state-space of the state-machines implementing the
transformation logic increases significantly with the number of open streams that a stage has to concurrently deal with.
We therefore categorize stream-of-streams transformations as a separate group.


Injecting Transformations
-------------------------

*swave* currently defines these injecting transformations:

- @ref[groupBy]
- @ref[injectBroadcast]
- @ref[injectRoundRobin]
- @ref[injectSequential]
- @ref[injectToAny]
- @ref[split]
- @ref[splitAfter]
- @ref[splitWhen]


Flattening Transformations
--------------------------

*swave* currently defines these flattening transformations:

- @ref[flatMap]
- @ref[flattenConcat]
- @ref[flattenMerge]
- @ref[flattenRoundRobin]
- @ref[flattenSorted]
- @ref[flattenToSeq]


Example
-------

As an example of a stream-of-streams application let's look at a (slightly simplified) implementation of the
@ref[takeEveryNth] transformation, which we call `takeEvery` here in order to avoid name clashes:

@@snip [-]($test/StreamOfStreamsSpec.scala) { #takeEvery }

This is a typical application of the @ref[injectSequential] transformation, which creates a sub stream, pushes as many elements
into it as the sub stream accepts, then opens the next sub stream and so on.
Every sub stream accepts `n` elements, of which only the last one is produced.
When all sub streams are concatenated we get exactly the kind of "take every n-th element" effect that we intended. 


Relationship to Fan-Ins and Fan-Outs
------------------------------------

The injecting and flattening transformations have a very close relationship to @ref[fan-outs] and @ref[fan-ins],
respectively.<br/>
Check out the the @ref[next chapter] for more details.


  [transformations]: overview.md
  [Simple Transformations]: simple.md
  [groupBy]: reference/groupBy.md
  [injectBroadcast]: reference/injectBroadcast.md
  [injectRoundRobin]: reference/injectRoundRobin.md
  [injectSequential]: reference/injectSequential.md
  [injectToAny]: reference/injectToAny.md
  [split]: reference/split.md
  [splitAfter]: reference/splitAfter.md
  [splitWhen]: reference/splitWhen.md
  [flatMap]: reference/flatMap.md
  [flattenConcat]: reference/flattenConcat.md
  [flattenMerge]: reference/flattenMerge.md
  [flattenRoundRobin]: reference/flattenRoundRobin.md
  [flattenSorted]: reference/flattenSorted.md
  [flattenToSeq]: reference/flattenToSeq.md
  [takeEveryNth]: reference/takeEveryNth.md
  [fan-outs]: fan-outs.md
  [fan-ins]: fan-ins.md
  [next chapter]: relationships.md