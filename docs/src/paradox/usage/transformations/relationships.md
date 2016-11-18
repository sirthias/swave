Fan-In/Out Relationships
========================

In streaming architectures, which are compatible with the @ref[Reactive Streams] protocol, information travels along a
sequence of streaming stages in both directions. Data elements flow downstream and demand signals flow upstream.
This has interesting consequences for @ref[fan-outs] and @ref[fan-ins] as well as @ref[injecting] and @ref[flattening].


Symmetry between Fan-Outs and Fan-Ins
-------------------------------------

@@@ p { .centered }
![Fan-Ins mirror Fan-Outs and vice versa](.../fan-in-out-mirror.svg)
@@@

As you can see, splitting the data (as in a @ref[fan-out]) requires merging the demand and merging the data
(as in a @ref[fan-in]) requires splitting the demand. Both structures are quite symmetric.

This symmetry is not superficial. It manifests itself also in the semantics of the individual transformations available
for both sides. For example, let's compare the @ref[fanOutRoundRobin] and the @ref[fanInRoundRobin] transformations.

The @ref[fanOutRoundRobin] stage distributes data elements coming in from its upstream across its downstreams in a
round-robin fashion. The first element goes to the first downstream, the second to the second, and so on. It doesn't
matter whether the respective target downstream has already signaled demand. If it hasn't the fan-out stage waits until
demand is signalled or the downstreams cancels.

The @ref[fanInRoundRobin] stage distributes demand coming in from its downstream across its upstreams in a round-robin
fashion. The request for the first element goes to the first upstream, the request for the second to the second, and
so on. It doesn't matter whether the respective target upstream has already delivered an element. If it hasn't the
fan-in stage waits until an element arrives or the upstream completes.

As you can see, the descriptions of the two transformations directly mirror each other.


Correspondance between Fan-Out/In and Injecting/Flattening
----------------------------------------------------------

In addition to the symmetry between @ref[fan-outs] and @ref[fan-ins] there is a strong correlation between
@ref[fan-out] and @ref[injecting] as well as between @ref[fan-in] and @ref[flattening].
  
Since @ref[Injecting Transformations] produce a "stream of downstreams" they can be regarded as a kind of
"dynamic fan-out", where the number of downstreams can vary across the life-time of the stream.<br/>
And similarly @ref[Flattening Transformations] must usually be able to deal with a dynamic number of active upstreams,
as they consume a "stream of upstreams".

This diagram shows the high-level relationships between the discussed transformation categories:

@@@ p { .centered }
![Relationship Diagram](.../relationships.svg)
@@@

  [Reactive Streams]: ../../introduction/reactive-streams.md
  [fan-outs]: fan-outs.md
  [fan-out]: fan-outs.md
  [fan-ins]: fan-ins.md 
  [fan-in]: fan-ins.md
  [fanOutRoundRobin]: reference/fanOutRoundRobin.md
  [fanInRoundRobin]: reference/fanInRoundRobin.md
  [injecting]: streams-of-streams.md#injecting-transformations
  [Injecting Transformations]: streams-of-streams.md#injecting-transformations
  [flattening]: streams-of-streams.md#flattening-transformations
  [Flattening Transformations]: streams-of-streams.md#flattening-transformations