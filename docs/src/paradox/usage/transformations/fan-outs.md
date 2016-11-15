Fan-Outs
========

Fan-Outs are @ref[stream graph components] with one input port and several output ports.
 
@@@ p { .centered }
![Fan-Out](.../fan-out.svg)
@@@

*swave's* streaming DSL allows you to define fan-outs in a flexible yet concise way.<br/>
Currently these fan-out variants are available:
 
 * @ref[fanOutBroadcast]
 * @ref[fanOutBroadcastBuffered]
 * @ref[fanOutFirstAvailable]
 * @ref[fanOutRoundRobin]
 * @ref[switch]
 * @ref[switchIf]

Additionally these fan-out shortcut transformations are defined:

* @ref[tee]


Basic Example
-------------

@@snip [-]($test/FanOutSpec.scala) { #basic-example }  

This example encodes this stream graph:

@@@ p { .centered }
![Fan-Out Example](.../fan-out-graph1.svg)
@@@

This graph has three @ref[drains], two producing a `Seq` to a `Promise` and one (the main one) producing a `String`. <br/>
As you can already (partially) see from this example a fan-out definition consists of

1. a call to a specific fan-out variant, in this case @ref[fanOutBroadcast]
2. the addition of one or more fan-out branches via `sub`
3. the definition of sub-branch @ref[transformations][] (as on any regular @ref[Spout]) after the `sub` call 
4. closing the subs
5. closing the fan-out
 
The last two points deserve some deeper explanation.

After you've finished the definition of a sub branch "pipeline" (following a `sub` call) there are two alternatives for
what to do with this open sub branch. You can either drain it into a @ref[Drain] with `.to(drain)` (as the first and
last sub branches do in the example above) or you can leave it open with `.end`.

After you've finished defining all sub branches there are two ways to "close" the fan-out. If one a single sub is left
open (with `.end`) you can use `.continue` to "pick-up" this open sub branch and simply continue appending more
@ref[transformations] to it. This sub then becomes the new "main line" of your stream graph.

If several subs are left open you cannot use `.continue`, because it wouldn't be clear which one to pick up and what to
do with the other open subs. In this case you can use one of the available @ref[fan-in] variants to "join" the open
subs and continue your stream graph definition in a fluent fashion. A common use case is a "diamond" graph.


Diamond Example
---------------

A common stream graph pattern is a "diamond" setup, where a fan-out first defines several sub branches, which each
apply some specific transformations to "their" elements, before the ends of the sub branches are re-joined with some
kind of @ref[fan-in] logic.

Here is a simple example:

@@snip [-]($test/FanOutSpec.scala) { #diamond }

Adding one or even more sub branches that aren't left open (i.e. drain into some @ref[Drain]) wouldn't affect the
fan-in in any way. And if you add another fan-out sub that is left open (i.e. ends with `.end`) the fan-in would still
work but produce a `Tuple3` instead of a `Tuple2`.


Mixing Fan-Outs and Fan-Ins
---------------------------

Mixing fan-outs and @ref[fan-ins] is possible even beyond what was shown in the diamon example above.<br/>
For example:

@@snip [-]($test/FanOutSpec.scala) { #mixed }
 
  [stream graph components]: ../basics.md#streams-as-graphs
  [Spout]: ../spouts.md
  [drains]: ../drains.md
  [Drain]: ../drains.md
  [transformations]: overview.md
  [fan-in]: fan-ins.md
  [fan-ins]: fan-ins.md
  [fanOutBroadcast]: reference/fanOutBroadcast.md
  [fanOutBroadcastBuffered]: reference/fanOutBroadcastBuffered.md
  [fanOutFirstAvailable]: reference/fanOutFirstAvailable.md
  [fanOutRoundRobin]: reference/fanOutRoundRobin.md
  [switch]: reference/switch.md
  [switchIf]: reference/switchIf.md
  [tee]: reference/tee.md