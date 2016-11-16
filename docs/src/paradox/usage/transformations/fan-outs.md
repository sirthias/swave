Fan-Outs
========

Fan-Outs are @ref[stream graph components] with one input port and several output ports.
 
@@@ p { .centered }
![Fan-Out](.../fan-out.svg)
@@@

Currently these fan-out variants are available:
 
* @ref[fanOutBroadcast]
* @ref[fanOutBroadcastBuffered]
* @ref[fanOutFirstAvailable]
* @ref[fanOutRoundRobin]
* @ref[switch]

Additionally these fan-out shortcut transformations are defined:

* @ref[tee]
* @ref[switchIf]


Basic Example
-------------

*swave's* streaming DSL allows you to define fan-outs in a flexible yet concise way.<br/>
Here is a basic example:

@@snip [-]($test/FanOutSpec.scala) { #basic-example }  

This example encodes this stream graph:

@@@ p { .centered }
![Fan-Out Example](.../basic-fan-out.svg)
@@@

This graph has three @ref[Drains], two producing a `Seq` to a `Promise` and one (the main one) producing a `String`. <br/>
As you can already (partially) see from this example a fan-out definition consists of

1. a call to a specific fan-out variant, in this case @ref[fanOutBroadcast]
2. the addition of one or more fan-out branches via `sub`
3. the definition of sub-branch @ref[transformations][] (as on any regular @ref[Spout]) after the `sub` call 
4. closing the subs
5. closing the fan-out
 
The last two points deserve some deeper explanation.

After you've finished the definition of a sub branch "pipeline" (following a `sub` call) there are two alternatives for
what to do with this open sub branch. You can either drain it into a @ref[Drain] with `.to(drain)` (as the first and
last sub branches do in the example above) or you can leave it open by simply calling `.end`.

After you've finished defining all sub branches there are two ways to "close" the fan-out. If only a single sub is left
open (with `.end`) you can use `.continue` to "pick-up" this open sub branch and simply continue appending more
@ref[transformations] to it. This sub then becomes the new "main line" of your stream graph.

If several subs are left open you cannot use `.continue`, because it wouldn't be clear which one to pick up and what to
do with the other open subs. In this case you can use one of the available @ref[fan-in] variants to "join" the open
subs and continue your stream graph definition in a fluent fashion. A common use case for this is a "diamond" graph.


Diamond Example
---------------

A common stream graph pattern is a "diamond" setup, where a fan-out first defines several sub branches, which each
apply some specific transformations to "their" elements, before the ends of the sub branches are re-joined with some
kind of @ref[fan-in] logic. For example this graph:

@@@ p { .centered }
![Diamond Graph](.../diamond.svg)
@@@

can be encoded like this:

@@snip [-]($test/FanOutSpec.scala) { #diamond }

Adding one or even more sub branches that aren't left open (i.e. drain into some @ref[Drain]) wouldn't affect the
fan-in in any way. And if you add another fan-out sub that is left open (i.e. ends with `.end`) the fan-in would work
just as well but produce a `Tuple3` instead of a `Tuple2`.


Mixing Fan-Outs and Fan-Ins
---------------------------

Mixing fan-outs and @ref[fan-ins] is possible even beyond what was shown in the examples above.<br/>
Take this code for example:

@@snip [-]($test/FanOutSpec.scala) { #mixed }

It encodes this graph:

@@@ p { .centered }
![Mixed Fan-Out/Fan-In Graph](.../mixed.svg)
@@@

This graph could be drawn in a simpler way but we show it like this in order to make it easier to correlate the DSL
code with the visual representation.

As you can see from this example you can `attach` @ref[Spouts] "from the outside" at any point in a fan-out definition,
even on the left (with `attachLeft`), which allows for very flexible and yet concise definition of the large majority
of common stream graphs. Some graphs, especially the ones containing cycles, cannot be constructed in a fully fluent
fashion. But with only one small additional element, namely @ref[Couplings], even these graphs, and in fact *all*
graphs, can be defined. 


captureResult and dropResult 
----------------------------

In the examples above you might have noticed the `.captureResult` and `.dropResult` calls, of which one is required for
most types of @ref[Drains] if they are to be used as the target of a fan-out sub branch.

The reason for this is that the DSL offers no way to access the result of a @ref[Drain] when it is used within a
fan-out sub branch. Therefore, in order to be usable in sub branch, a drain is *required* to not produce a result,
which means that its result type must be `Unit`. Since most types of drains *do* produce results they must be explicitly
transformed into @ref[Drains] without result.

There are two options for this:

1. capture the result in a `Promise`
2. drop the result completely

@ref[Drains] define transformation methods for both of these alternatives, which are named accordingly. 

 
  [stream graph components]: ../basics.md#streams-as-graphs
  [Spout]: ../spouts.md
  [Spouts]: ../spouts.md
  [Drains]: ../drains.md
  [Drain]: ../drains.md
  [transformations]: overview.md
  [fan-in]: fan-ins.md
  [fan-ins]: fan-ins.md
  [Couplings]: couplings.md
  [fanOutBroadcast]: reference/fanOutBroadcast.md
  [fanOutBroadcastBuffered]: reference/fanOutBroadcastBuffered.md
  [fanOutFirstAvailable]: reference/fanOutFirstAvailable.md
  [fanOutRoundRobin]: reference/fanOutRoundRobin.md
  [switch]: reference/switch.md
  [switchIf]: reference/switchIf.md
  [tee]: reference/tee.md