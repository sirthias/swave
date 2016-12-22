Sync vs. Async
==============

As already pointed out in the @ref[Quick Start] and @ref[Basics] chapters *swave* supports running streams
synchronously, on the caller thread, as well as asynchronously, off the caller thread, on one or more configurable
thread-pools.


Synchronous Execution
---------------------

Running a stream synchronously on the caller thread means that the `streamGraph.run()` call will not only *start* the
stream execution but actually *perform* the stream execution, in its entirety. Since no inter-thread communication is
required in these cases the overall runtime overhead introduced by the streaming infrastructure is quite small, which
can cause simple streams to run much faster than in comparable asynchronous setups.

However, since everything runs only on one thread there is no way to make use of any potentially existing parallelism in
the hardware. Also, the stream cannot contain any stages that need to react to signals from outside the stream (like
timers, interrupts, callbacks, etc.). The respective reference documentation for @ref[transformations], @ref[Spouts] and
@ref[Drains] will tell you whether the underlying stage is able to run synchronously or not.

If a stream runs synchronously and `run().result` returns a @scaladoc[Future] then the returned `Future` instance will
be already completed when the `run()` call returns. It might be though that the `Future` is completed with an error
(i.e. a @scaladoc[Failure] instance) rather than the expected result value, for example because some element of the
stream graph threw an exception during execution. The `run()` call itself will never throw an exception that is
triggered during the runtime of a stream. It will throw an exception though when the stream cannot be started because
the stream definition is illegal (e.g. because there are still unconnected ports).
If you need something is guaranteed to *never* throw use `tryRun()`. 

Here is an example of a @ref[map] stage that throws an exception when processing one particular stream element:

@@snip [-]($test/SyncVsAsyncSpec.scala) { #stream-failure }

Note that "running synchronously" doesn't mean that there is blocking involved! The limits on what can run synchronously
are established precisely because no stage is allowed to ever block. Whenever a stage might have a reason to block, e.g.
because it needs to wait for the completion of a @scaladoc[Future], it cannot run synchronously and needs to be
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


### `async`

Suppose now that we'd like to run this stream graph as is but off the caller thread, e.g. because it takes some longer
amount of time to finish and we'd like to use the caller thread for something else.

We can do so by simply adding an @ref[async] transformation to the graph at an arbitrary position:
 
@@snip [-]($test/SyncVsAsyncSpec.scala) { #async } 

Now the `drainToList(...)` returns immediately, likely before the produced `Future` value has been fulfilled.
We therefore have to explicitly await the `Future` value in order to get a hold of it. <br/>
(Note that `await` is **blocking** and therefore only allowed in exceptional circumstances, like this test here!)

The graph still looks the same but is now run on the default dispatcher. Since no asynchronous boundaries have been
introduced it will still run as one single block:

@@@ p { .centered }
![Basic Async Stream Graph](.../async-graph1.svg)
@@@

The @ref[async] transformation has no effect on the data or the demand travelling through it. All it does is forcing
the stream graph it's part of into running asynchronously. It has one optional parameter: the name of a @ref[configured]
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
If we wanted to we could assign a custom dispatcher by adding a `.async(dispatcherName)` somewhere. 
 

### Explicit Async Boundaries

While simply moving stream execution away from the caller thread is nice it doesn't really help with running things in
parallel. In order to do that we need to introduce asynchronous boundaries, which can be done with @ref[asyncBoundary]:

@@snip [-]($test/SyncVsAsyncSpec.scala) { #async-boundary }

@ref[asyncBoundary] splits the stream graph into two *async regions*, which are sub-graphs that run independently from
each other on their own dispatchers:
 
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

Like @ref[async] the @ref[asyncBoundary] transformation takes as optional parameter the name of a dispatcher that is to
be assigned to all stages in its *upstream* region. The dispatcher for its downstream region can be defined by a
potentially existing other @ref[asyncBoundary] further downstream or an `.async(dispatcherId)` marker. 


### Complex Example

Of course stream graphs aren't always straight pipelines, so let's look at a more complex example involving several
drains and async boundaries as well as custom dispatcher assignments:

![Complex Stream Graph with several Async Boundary](.../async-graph4.svg)

As you can see it's possible that both sides of an async boundary belong to the same async region if there is another
boundary-less connection.

Here is the code for this (admittedly quite contrived) example:
 
@@snip [-]($test/SyncVsAsyncSpec.scala) { #complex-example }

In stream graphs with more than one async region a graceful shutdown requires attaching to the `run.termination`
(a `Future[Unit]`), which will only be completed when all parts of the stream graph have fully terminated.

Simply calling `env.shutdown` as soon as (some) drain result has become available might cause the `StreamEnv` to shut
down its thread-pools before all regions have had the chance to properly run their cleanup code. This might cause
the appearance of `java.util.concurrent.RejectedExecutionException: Dispatcher '...' has already shut down` errors
in your logs.

Since this mechanism for shutting down gracefully is so common *swave* already provides the `shutdownOn` method on
`StreamEnv`, which internally attaches the shutdown call to the given termination future. 


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
  [asyncBoundary]: ../transformations/reference/asyncBoundary.md
  [withCompletionTimeout]: ../transformations/reference/withCompletionTimeout.md
  [asynchronous transformation]: ../transformations/simple.md#asynchronous-simple-transformations
  [configured]: configuration.md