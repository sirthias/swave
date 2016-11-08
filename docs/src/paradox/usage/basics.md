Basics
======

In order to be able to work with *swave* effectively you'll need to have a basic understanding of how *swave* operates
under the hood. With the right mental model it'll be much easier to come up with the initial design of a streaming
application as well as find and fix problems later on.
 
This chapter also introduces some core terminology, which is used throughout the rest of the documentation.
 
 
Streams as Graphs
-----------------

A good way to envision a *swave* stream setup is as a small in-memory network in which all @ref[spouts], @ref[drains]
and @ref[transformations] are the nodes and data flow along the connections between them, e.g. like this:

![Basic Stream Graph](.../basic-stream-graph.svg)

The arrows in this diagram show the "forward" direction, i.e. the direction in which data elements flow through the
network. Since the network can also contain cycles (i.e. loops) it formally forms a "Directed Graph".

In *swave* all the basic graph nodes (i.e. @ref[spouts], @ref[transformations] and @ref[drains]) are called
**Stages**. All communication between stages happens along the graph edges in the form of **Signals**, which travel
either in the forward direction (i.e. **downstream** in the same direction as the data elements) or backwards (i.e.
**upstream** against the flow of data elements), depending on the signal's type. 


The Core Signals
----------------

*swave* implements all stages as small and largely decoupled state machines that are driven by the signals coming in
from the neighbouring stages. Once a stream graph is running there are five core types of signals that make everything
tick and which almost directly correspond to their counterparts in the
@ref[Reactive Streams](../introduction/reactive-streams.md) protocol:

@@@ p { .centered }
![The five basic Signals](.../five-signals.svg)
@@@
 
Request(n: `Long`)
: Signals that the downstream stage is ready to receive the next `n` data elements.<br/>
The total number of requested elements is called **demand** and the lack thereof **backpressure**.
  
Cancel
: Signals that the downstream stage is no longer interested in receiving data elements and will (potentially) shut down.

OnNext(element)
: Delivers the next data element to the downstream stage.

OnComplete
: Signals that the upstream stage will deliver no more data elements and will (potentially) shut down.

OnError(error: `Throwable`)
: Signals that the upstream stage has encountered an error and will shut down.


After a stream is started the machinery is kicked into motion by the first `Request` signal dispatched (in most cases)
by the final @ref[Drain] at the very end of the pipeline. This `Request` signal triggers respective state machine
actions in the upstream stages which cause the dispatch of more signals that traverse the whole graph in a kind of
ripple effect. As a result of this process data elements begin to flow from the spouts towards the drains where they
can.

The key thing to realize here is that **nothing will happen unless sufficient demand is signalled from downstream**
via one or more `Request` signals. In most cases you won't have to worry about all the details of this process, but
especially when things don't work as expected you might have to understand these lower-level principles in order to
@ref[debug the problem](debugging.md).  

 
Stream Life-Cycle
-----------------

All stages in *swave* are single-use, which means that they go through their life-cycle at most **once**.<br/>
This section outlines what this life-cycle looks like.

### Connecting and Closing

When you create a new stage (e.g. a @ref[spout] or a @ref[drain]) it starts out in its initial state, where it accepts
connections from other stages on its "open ends" (also called **ports**). These connections are typically created
automatically by the DSL.

Let's look at this simple example:

@@snip [-](.../BasicSpec.scala) { #foo }

@@@ p { .centered }
![Spout foo](.../foo-spout.svg)
@@@

The spout instance `foo` has no downstream attached yet, i.e. it has one open port. We can attach
a @ref[transformation] to connect the open port to a downstream stage:

@@snip [-](.../BasicSpec.scala) { #upperFoo }

@@@ p { .centered }
![Spout upperFoo](.../upper-foo.svg)
@@@

After we've done this the `foo` instance is fully connected. Any attempt to reuse it (e.g. by trying to attach another
transformation) will fail with an @scaladoc[IllegalReuseException]. However, attaching the `map` transformation to the
`foo` instance produces another spout (called `upperFoo` in this case), which itself has an unconnected (open) port.

As you can see attaching @ref[transformations] to @ref[spouts] will always connect up some ports but at the same time
produce new unconnected ones. Only by attaching @ref[drains] can a graph become fully **closed**, without any ports
left unconnected:

@@snip [-](.../BasicSpec.scala) { #piping }

@@@ p { .centered }
![Simple Piping](.../simple-piping.svg)
@@@


### Sealing

As you might have already seen in the @ref[Quick-Start] chapter a graph has to be closed first, by connecting up all
open ports, before it can be started. Most of the time this is hard to get wrong because the DSL will only give you a
@scaladoc[Piping] if the final port is connected to a @ref[drain], and a stream can only be started
via a @scaladoc[Piping].<br/>
Sometimes however, for example when using @ref[Couplings], there is a chance that some ports are not connected yet
when the stream is started. Therefore, just before starting, *swave* sends a special `xSeal` signal across all stages
of the graph, which causes them to verify their being fully connected. If any port is still unconnected the stream will
immediately fail with an @scaladoc[UnclosedStreamGraphException].

A stream graph can only be sealed once. Trying to seal it a second time will result in an
@scaladoc[IllegalReuseException].


### Starting

Once the stream setup has been sealed successfully it can be started. It is only at this point, when the stream is
started, that any resources are claimed, which are potentially required by a stage in the graph (e.g. a network socket,
a file handle or a thread-pool). Before the start, up until and including the sealing, no component in the stream will
become active, which means that you are free to inspect it, maybe @ref[render] it, and potentially drop it without
having to worry about any clean-up.
  
After having been started most stages will either begin to immediately send out signals to their peers or wait for
signals from their peers, depending on their own logic and configuration. In the process data elements will start to
flow from the @ref[spouts] to the @ref[drains].

A @scaladoc[Piping] can only be started once. Trying to start it a second time will result in an
@scaladoc[IllegalReuseException].


### Running

After having been started the stream will be running until all stages have terminated. "Running" thereby means that
the state machines within the stages wait for signals from their peers (or from the outside) and react with sending
signals themselves.

The exact mechanics of how this happens are not necessarily interesting but it is helpful to know the few basic rules
that define which signal is allowed to be sent when:

`request` before `cancel`
: There can be zero or more `request` signals before a `cancel` signal, but no `request` after a `cancel`. A `cancel`
is always the last signal that is sent from a stage to upstream. It is allowed that a stage does not request anything
from its upstream and cancels immediately.
 
No unrequested elements
: An upstream is only ever allowed to send as many data elements to a downstream (via `onNext` signals) as have been
previously requested (in total) by that downstream. This means that, without prior demand, no data element can be
delivered from the upstream to the downstream.

`onNext` before `onComplete` or `onError`
: There can be zero or more `onNext` signals before an `onComplete` or `onError`, but no `onNext` afterwards.
A termination signal (`onComplete` or `onError`) is always the last signal that is sent from a stage to downstream.
It is allowed that a stage does not deliver any data to a downstream via `onNext` but immediately signals termination.

Termination signals don't need demand
: When an upstream knows that no further data elements will follow it can immediately signal `onComplete` to its
downstream, even when no demand has been previously signalled from there. Similarly, in case of an error a stage
usually and immediately signals `onError` to all its downstreams, cancels its upstreams and shuts down.

Completion is buffered but errors are not
: In many cases a stream graph contains explicit or implicit buffers at various points in its stage network. Apart from
potentially increasing throughput buffers are sometimes necessary to generate required demand.
It is important to understand that `onComplete` signals are buffered, i.e. queued behind potentially preceding data
elements, whereas `onError` signals are not! This means that errors can (and often do) "jump over" data elements that
were delivered before the error but are still sitting in some buffer.


### Terminating

A stream graph is fully terminated when all its stages have shut down. When exactly a stage shuts down depends on the
stage's logic. The basic @ref[spout] stages and most simple transformation stages shut down when they have received a
`cancel` from downstream or a termination signal from upstream. However, this is not necessarily the case for
@ref[fan-in] and @ref[fan-out] stages.

Many times you are only interested in completion of the result `Future` that a final @ref[drain] produces. However,
depending on the stream's execution mode, this might well be before all stages have terminated. It could even be that
the stream continues to run indefinitely afterwards if it's set up in a way that allows this.
 
 
Prefer `def` over `val`
----------------------- 

One simple way to deal with the non-reusability of *swave's* stream components is to model them as a `def` rather than
a `val` wherever reuse is desired, e.g. like this:

@@snip [-](.../BasicSpec.scala) { #reuse }

Apart from ensuring that you'll never see an @scaladoc[IllegalReuseException] it also has the benefit that
parameterizing your higher-level stream constructs becomes trivial (as all that's required is giving the `def` a
parameter list). 
 

Execution Model
---------------

*swave* streams can run in one of two basic modes:

1. Synchronously on the caller thread (yet without any blocking!)
2. Asynchronously on a thread-pool detached from the caller thread

Hereby the caller thread is the thread calling the `run` method. 

Whether a stream runs synchronously or asynchronously depends on the kinds of stages that are present in the graph.
By default *swave* will run the stream synchronously if possible, but certain kinds of stages must react to external
signals (like timers or interrupts) and thus cannot operate in a synchronous mode. If at least one of these is present
in the graph then the stream will run asynchronously.

Of course you can also force a stream to run asynchronously in various ways.
Check out the chapter on @ref[Sync vs. Async Execution] for more details on this.


Thread Safety
-------------

While the running of streams is specifically designed for asynchronous execution the DSL for constructing stream graphs
is not. This means that, if you (for example) try to reuse a stage from two different threads without proper
synchronization, the behavior you see might be unexpected.

The DSL is designed for use from only one thread at a time. If you share DSL elements across threads you must make sure
to properly synchronize all accesses yourself.


  [spout]: spouts.md
  [spouts]: spouts.md
  [transformation]: transformations/index.md
  [transformations]: transformations/index.md
  [drain]: drains.md
  [drains]: drains.md
  [IllegalReuseException]: swave.core.IllegalReuseException
  [UnclosedStreamGraphException]: swave.core.UnclosedStreamGraphException
  [Piping]: swave.core.Piping
  [Quick-Start]: quick-start.md
  [render]: rendering.md
  [Couplings]: transformations/couplings.md
  [fan-in]: transformations/fan-ins.md 
  [fan-out]: transformations/fan-outs.md 
  [Sync vs. Async Execution]: sync-vs-async.md
