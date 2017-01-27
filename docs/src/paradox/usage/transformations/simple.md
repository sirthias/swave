Simple Transformations
======================

Simple @ref[transformations] are @ref[stream graph components] with one input port and one output port.
They "simply" transform a @ref[Spout] of one type into a @ref[Spout] of another or even the same type. 

@@@ p { .centered }
![A Simple Transformation](.../simple-transformation.svg)
@@@

Simple transformations are attached to a @ref[Spout] through a call to the respective DSL method that is defined
in the `StreamOps` trait (see also the @scaladoc[StreamOps Scaladoc] or @github[StreamOps Source Code]).
   
For example:

@@snip [-]($test/SimpleTransformSpec.scala) { #example }

To reiterate the explanations from the @ref[Quick Start] and @ref[Basics] chapters: Calling a method like `.map(...)` on
a @ref[Spout] doesn't trigger any immediate action except for the attachment of a `Map` stage to the open port of the
underlying @ref[Spout].<br/>
No data start to flow and no elements are actually being mapped.
 
Only when the stream is @ref[started and run] will the `Map` stage spring to life and apply its logic to the incoming
data elements.


## Synchronous Simple Transformations

* @ref[buffer](reference/buffer.md)
* @ref[bufferDropping](reference/bufferDropping.md)
* @ref[collect](reference/collect.md)
* @ref[conflate](reference/conflate.md)
* @ref[conflateToLast](reference/conflateToLast.md)
* @ref[conflateWithSeed](reference/conflateWithSeed.md)
* @ref[deduplicate](reference/deduplicate.md)
* @ref[drop](reference/drop.md)
* @ref[dropAll](reference/dropAll.md)
* @ref[dropLast](reference/dropLast.md)
* @ref[dropWhile](reference/dropWhile.md)
* @ref[duplicate](reference/duplicate.md)
* @ref[elementAt](reference/elementAt.md)
* @ref[expand](reference/expand.md)
* @ref[filter](reference/filter.md)
* @ref[filterNot](reference/filterNot.md)
* @ref[first](reference/first.md)
* @ref[fold](reference/fold.md)
* @ref[foldAsync](reference/foldAsync.md)
* @ref[grouped](reference/grouped.md)
* @ref[groupedTo](reference/groupedTo.md)
* @ref[headAndTail](reference/headAndTail.md)
* @ref[last](reference/last.md)
* @ref[logSignal](reference/logSignal.md)
* @ref[map](reference/map.md)
* @ref[multiply](reference/multiply.md)
* @ref[nop](reference/nop.md)
* @ref[onCancel](reference/onCancel.md)
* @ref[onComplete](reference/onComplete.md)
* @ref[onElement](reference/onElement.md)
* @ref[onError](reference/onError.md)
* @ref[onRequest](reference/onRequest.md)
* @ref[onSignal](reference/onSignal.md)
* @ref[onSignalPF](reference/onSignalPF.md)
* @ref[onStart](reference/onStart.md)
* @ref[onTerminate](reference/onTerminate.md)
* @ref[prefixAndTail](reference/prefixAndTail.md)
* @ref[prefixAndTailTo](reference/prefixAndTailTo.md)
* @ref[protect](reference/protect.md)
* @ref[recover](reference/recover.md)
* @ref[recoverToTry](reference/recoverToTry.md)
* @ref[recoverWith](reference/recoverWith.md)
* @ref[reduce](reference/reduce.md)
* @ref[scan](reference/scan.md)
* @ref[scanAsync](reference/scanAsync.md)
* @ref[slice](reference/slice.md)
* @ref[sliceEvery](reference/sliceEvery.md)
* @ref[sliding](reference/sliding.md)
* @ref[slidingTo](reference/slidingTo.md)
* @ref[take](reference/take.md)
* @ref[takeEveryNth](reference/takeEveryNth.md)
* @ref[takeLast](reference/takeLast.md)
* @ref[takeWhile](reference/takeWhile.md)
* @ref[withLimit](reference/withLimit.md)
* @ref[withLimitWeighted](reference/withLimitWeighted.md)
 
## Asynchronous Simple Transformations 

* [async](reference/async.md)
* [asyncBoundary](reference/asyncBoundary.md)
* [delay](reference/delay.md)
* [dropWithin](reference/dropWithin.md)
* [groupedWithin](reference/groupedWithin.md)
* [mapAsync](reference/mapAsync.md)
* [mapAsyncUnordered](reference/mapAsyncUnordered.md)
* [sample](reference/sample.md)
* [throttle](reference/throttle.md)
* [takeWithin](reference/takeWithin.md)
* [withCompletionTimeout](reference/withCompletionTimeout.md)
* [withIdleTimeout](reference/withIdleTimeout.md)
* [withInitialTimeout](reference/withInitialTimeout.md)

  [transformations]: overview.md
  [stream graph components]: ../basics.md#streams-as-graphs
  [Spout]: ../spouts.md
  [StreamOps Scaladoc]: swave.core.StreamOps
  [StreamOps Source Code]: /core/src/main/scala/swave/core/StreamOps.scala
  [Quick Start]: ../quick-start.md
  [Basics]: ../basics.md
  [started and run]: ../quick-start.md#running-a-stream
