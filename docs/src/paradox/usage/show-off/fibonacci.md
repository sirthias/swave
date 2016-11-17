Fibonacci Example
=================

This example shows how you can use a stream graph to model recursive algorithms like the generation of the stream of
all fibonacci numbers.

Here is a very simple way to create an infinite stream of all Fibonacci numbers that relies on an `unfold` @ref[Spout]:

@@snip [-]($test/FibonacciSpec.scala) { #unfold }

While this is a concise and efficient implementation the recursion required for the stream generation is hereby provided
by the "unfolding" feature. As such it is "built-in" and not that interesting from a "show-off" perspective.
  
A maybe more illustrative way to construct the same stream is the following one, which makes the recursion explicit in
the stream graph structure:

@@snip [-]($test/FibonacciSpec.scala) { #cycle }

The stream graph defined by this code can be visualized like this:

@@@ p { .centered }
![Fibonacci Example Stream Graph](.../fibobacci-graph.svg)
@@@

As you can see this graph contains a cycle, which is the source for the infinite nature of the stream.<br/>
Let's understand how this setup works by tracing the initial phase of the stream execution step by step:

1. When the stream is started three @ref[stages] begin to actively send signals: the main @ref[Drain] at the very end,
   the @ref[buffer] stage in the first fan-out sub branch and the @ref[sliding] stage right behind it.
   All 3 stages signal demand to the upstreams. The main drain signals demand for `Int.MaxValue` elements and the
   @ref[buffer] and @ref[sliding] stages each signal demand for 2 elements. 
   
2. The @ref[take] stage receives the demand signal from its downstream and forwards it as `Request(8)` to its own
   upstream, which, via the `subContinue`, is the @ref[fanOutBroadcast] stage.
     
3. The @ref[fanOutBroadcast] stage receives the demand signal for 2 elements (from the @ref[buffer] stage) on its first
   sub stream and a demand of 8 on its second sub stream. It therefore signals a demand of 2 to its own upstream,
   the @ref[concat].
     
4. The @ref[concat] stage forwards this demand to its first upstream, the `Spout(0, 1)`, which immediately delivers
   two elements and signals completion.
   
5. The @ref[concat] stage forwards the two received elements (`0` and `1`) to its downstream and registers the
   completion of its first upstream, which will cause all still unfulfilled as well as future demand to go to its
   second upstream (the `out` side of the @ref[Coupling]).
    
6. The @ref[fanOutBroadcast] stage receives the two elements (`0` and `1`) and forwards them to both of its downstreams.
  
7. The @ref[take] stage forwards the elements to the main @ref[Drain] and registers that it has seen the first two of
   the total 8 elements it is expecting.
   
8. The @ref[buffer] stage receives the two elements and immediately forwards them to the @ref[sliding] stage, which has
   already demanded them at the very start of the stream. Then the @ref[buffer] stage immediately re-requests two more
   elements from the @ref[fanOutBroadcast] stage.
   
9. The @ref[fanOutBroadcast] stage now has unfulfilled demand of 2 on its first sub and 6 on its seconds. It therefore
   signals demand of 2 to the @ref[concat] stage, which, via the @ref[Coupling] and the @ref[map], arrives at the
   @ref[sliding] stage.
   
10. The @ref[sliding] stage produces its first window, a `Seq(0, 1)` to the @ref[map], which in result produces a `1`
   element to the @ref[concat] stage (again, via the @ref[Coupling]). Then it signals demand for the next element to
   the @ref[buffer] stage.
   
11. The @ref[concat], which currently has unfulfilled demand of 2, receives the `1` element and produces it to the
   @ref[fanOutBroadcast], which pushes it to both of its sub streams, where it arrives at the main @ref[Drain] as well
   as the @ref[buffer].

12. As the @ref[buffer] has already seen demand of `1` from its downstream it immediately forwards this element to the
   @ref[sliding] stage, which produces the next window, a `Seq(1, 1)` to the @ref[map], which in result produces a `2`
   to the @ref[concat], which forwards it to the @ref[fanOutBroadcast], which produces it to both its downstreams.
   
At this point a continuous cycle of demand and element production has been established and will run as long as nothing
stops the process. In this case the process will be stopped by the @ref[take] stage. After it has received 8 elements
it sends a `Cancel` signal to its upstream, the @ref[fanOutBroadcast]. 
Since that is configured with `eagerCancel = true` it will not continue to run "on one leg" (i.e. with only on sub
stream left), but rather forward the `Cancel` signal to its own upstream, which causes all stages in the cycle to be
stopped by the propagating cancellation.


Learnings
---------

When you play around with the code of this example you'll notice that things won't work when you remove the
@ref[buffer] stage, reduce its size or change it's `RequestStrategy`. The reason is that a certain amount of demand is
required *in the cycle* in order to kick things into motion and establish a perpetual flow of elements.
When you follow the initial sequence of events closely, as outlined above, you'll see why this is the case.

The learning here is that, while cycles are a perfectly valid and useful streaming pattern, they can require additional
@ref[buffer] stages to work correctly. Figuring out what exactly the minimum required buffer size is can be hard to
figure out by simply looking at the stage setup. Starting out with a bigger buffer and reducing it until things break
can be a good and pragmatic approach.
 
A second learning is that, if you have perpetual cycles, you need to think about a way to stop the stream if it's not
supposed to run forever. A @ref[fanOutBroadcast] configured with `eagerCancel = true`, as shown in this example,
is one possible way.
     

  [Spout]: ../spouts.md
  [Drain]: ../drains.md
  [stages]: ../basics.md
  [buffer]: ../transformations/reference/buffer.md
  [fanOutBroadcast]: ../transformations/reference/fanOutBroadcast.md
  [concat]: ../transformations/reference/concat.md
  [take]: ../transformations/reference/take.md
  [sliding]: ../transformations/reference/take.md
  [map]: ../transformations/reference/take.md
  [Coupling]: ../transformations/couplings.md