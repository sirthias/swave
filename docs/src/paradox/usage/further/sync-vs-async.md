Sync vs. Async
==============

As already pointed out in the @ref[Quick Start] and @ref[Basics] chapters *swave* supports running streams
synchronously, on the caller thread, as well as asynchronously, off the caller thread, on one or more configurable
thread-pools.


Synchronous Execution
---------------------

Running a stream synchronously on the caller thread means that the `piping.run()` call will not only *start* the stream
execution but actually *perform* the stream execution, in its entirety. Since no inter-thread communication is required
in these cases the overall runtime overhead introduced by the streaming infrastructure is quite small, which can cause
simple streams to run much faster than in comparable asynchronous setups.

However, since everything runs only on one thread there is no way to make use of any potentially existing parallelism in
the hardware. Also, the stream cannot contain any stages that need to react to signals from outside the stream (like
timers, interrupts, callbacks, etc.). The respective reference documentation for @ref[transformations], @ref[Spouts] and
@ref[Drains] will tell you whether the underlying stage is able to run synchronously or not.

If a stream runs synchronously and the `run()` call returns a @scaladoc[Future] then the returned `Future` instance will
be already completed when the `run()` call returns. It might be though that the `Future` is completed with an error
(i.e. a @scaladoc[Failure] instance) rather than the expected result value, for example because some element of the
stream graph threw an exception during execution. The `run()` call should never throw an exception itself.

Here is an example of a @ref[map] stage that throws an exception when processing one particular stream element:

@@snip [-]($test/SyncVsAsyncSpec.scala) { #stream-failure }

Note that "running synchronously" doesn't mean that there is blocking involved! The limits on what can run synchronously
are established precisely because no stage is allowed to ever block. Whenever a stage might have a reason to block, e.g.
because it needs to wait for the completion of a @scaladoc[Future], it cannot run synchronously anymore and needs to be
notified of the event it is interested in in an asynchronous fashion.


Asynchronous Execution
----------------------  
    
*swave* will run a stream synchronously whenever it can, which is only the case when all @ref[Spouts], @ref[Drains] and
@ref[transformations] in the stream graph (including potentially existing nested graphs) support this mode of execution.

To better understand how *swave* will behave by default and how you can control asynchronous execution in a fine-grained
fashion let's look at a simple example:
 
@@snip [-]($test/SyncVsAsyncSpec.scala) { #base-example } 
 
Here is the stream graph of this example, which also shows which stage will run on which thread / dispatcher:
 
@@@ p { .centered }
![Basic Example Stream Graph](.../async-graph0.svg)
@@@ 
 
As you can see everything will run on the caller thread, i.e. synchronously.


### Drain::async

Suppose now that we'd like to run this stream graph as is but off the caller thread, e.g. because it takes some longer
amount of time to finish and we'd like to use the caller thread for something else.

We can do so by simply marking the @ref[Drain][] (in fact any drain in the primary graph) with `.async()`:
 
@@snip [-]($test/SyncVsAsyncSpec.scala) { #drain-async } 

Now the `drainTo()` (which internally calls `run()`) returns immediately, likely before the produced `Future` value has
been fulfilled. We therefore have to explicitly await the `Future` value in order to get a hold of it. <br/>
Note that `await` is **blocking** and therefore only allowed in exceptional circumstances, like this test here!)

The graph still looks the same but is now run on the default dispatcher. Since no asynchronous boundaries have been
introduced it will still run as one single block:

@@@ p { .centered }
![Basic Async Stream Graph](.../async-graph1.svg)
@@@

The `.async()` method available on @ref[Drains] marks the underlying @ref[Drain] as asynchronous, which causes the
stream graph it's incorporated into to run asynchronously. It has one optional parameter: the name of a @ref[configured]
dispatcher to be used. If not specified then the default dispatcher will be assigned if no other assignment has been
made for the *async region* the drain is placed in (we'll talk about async regions in a bit.)


### Async Transformations

Another way to move the execution of a stream graph off the caller thread is the addition of an
@ref[asynchronous transformation] that cannot run synchronously. We could for example add an @ref[withCompletionTimeout]
transformation to the graph to make sure it will never run for longer than one second:
 
@@snip [-]($test/SyncVsAsyncSpec.scala) { #withCompletionTimeout } 

Since @ref[withCompletionTimeout] forces *swave* to run the graph on some dispatcher the stream graph of this example
looks like this:

@@@ p { .centered }
![Stream Graph with Async Transformation](.../async-graph2.svg)
@@@

Because nothing prescribes the use of a specific dispatcher *swave* will assign the default dispatcher to all stages.<br/>
If we wanted to we could assign a custom dispatcher by marking the @ref[Drain] with `.async(dispatcherName)`. 
 

### Explicit Async Boundaries

While simply moving stream execution away from the caller thread is nice it doesn't really help with running things in
parallel. In order to do that we need to introduce asynchronous boundaries, which can be done with @ref[async]:

@@snip [-]($test/SyncVsAsyncSpec.scala) { #async-boundary }

@ref[async] splits the stream graph into two *async regions*, which are sub-graphs that run independently from each
other on their own dispatchers:
 
@@@ p { .centered }
![Stream Graph with Async Boundary](.../async-graph3.svg)
@@@ 

If the default dispatcher is configured to use more than one thread (as it usually is) this stream setup can potentially
keep two cores busy, because both async regions can run concurrently on separate threads.
 
By adding more async boundaries we could further increase the parallelization potential, i.e. possibly increase
throughput, at the expense of higher latency because each async boundary introduces some overhead resulting from
inter-thread communication. Our toy example here will definitely not benefit from async boundaries because the
individual stages are too light-weight. Any benefit from parallelization will be completely outweighed by the async
boundary's overhead. In real-world applications however, where the stream stages do actual and potentially heavy work,
the ability to quickly and easily add async boundaries at different points and evaluating their effect on application
performance is an important tool. 

Like the `.async()` method on @ref[Drains] the @ref[async] transformation takes as optional parameter the name of a
dispatcher that is to be assigned to all stages in its *upstream* region. The dispatcher for its downstream region
can be defined by a potentially existing other async boundary further downstream or an `.async(dispatcherId)` marker on
a @ref[Drain]. 


### Complex Example

Of course stream graphs aren't always straight pipelines, so let's look at a more complex example involving several
drains and async boundaries as well as custom dispatcher assignments:

... TODO ...


  [Quick Start]: ../quick-start.md#running-a-stream
  [Basics]: ../basics.md#execution-model
  [transformations]: ../transformations/reference/index.md                                      
  [Spouts]: ../spouts.md                                      
  [Drains]: ../drains.md
  [Drain]: ../drains.md
  [Future]: scala.concurrent.Future
  [Failure]: scala.util.Failure
  [map]: ../transformations/reference/map.md
  [async]: ../transformations/reference/async.md
  [withCompletionTimeout]: ../transformations/reference/withCompletionTimeout.md
  [asynchronous transformation]: ../transformations/simple.md#asynchronous-simple-transformations
  [configured]: configuration.md