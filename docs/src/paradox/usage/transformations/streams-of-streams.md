Streams-of-Streams
==================

Streams, like most other abstractions, can be nested.<br/>
This means that you can have a `Spout[Spout[T]]` like you can have a `List[List[T]]`.

*swave* offer a number of @ref[transformations] that either create a stream-of-streams from
in incoming stream of "regular" elements or flatten a stream-of-streams back to an outgoing stream of "regular"
elements.
 
@@@ p { .centered }
![Stream-of-Stream Transforms](.../sos-transforms.svg)
@@@ 

While the shape of these stream-of-streams transformations is the same as for @ref[Simple Transformations] the internal
and external complexity is significantly higher. This is because the state-space of the state-machines implementing the
transformation logic increases significantly with the number of open streams that a stage has to concurrently deal with.
We therefore categorize stream-of-streams transformations as a separate group.


Creating Streams-of-Streams
---------------------------

*swave* currently defines these "creating streams-of-streams" transformations:

- @ref[groupBy]
- @ref[headAndTail]
- @ref[inject]
- @ref[prefixAndTail]
- @ref[prefixAndTailTo]
- @ref[split]
- @ref[splitAfter]
- @ref[splitWhen]


Flattening Streams-of-Streams
-----------------------------

*swave* currently defines these "flattening streams-of-streams" transformations:

- @ref[flatMap]
- @ref[flattenConcat]
- @ref[flattenMerge]


  [transformations]: overview.md
  [Simple Transformations]: simple.md
  [inject]: reference/inject.md
  [groupBy]: reference/groupBy.md
  [headAndTail]: reference/headAndTail.md
  [prefixAndTail]: reference/prefixAndTail.md
  [prefixAndTailTo]: reference/prefixAndTailTo.md
  [split]: reference/split.md
  [splitAfter]: reference/splitAfter.md
  [splitWhen]: reference/splitWhen.md
  [flatMap]: reference/flatMap.md
  [flattenConcat]: reference/flattenConcat.md
  [flattenMerge]: reference/flattenMerge.md