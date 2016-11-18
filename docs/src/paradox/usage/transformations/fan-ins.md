Fan-Ins
=======

Fan-Ins are @ref[stream graph components] with several input ports and a single output port.
 
@@@ p { .centered }
![A Fan-In](.../fan-in.svg)
@@@

*swave's* streaming DSL allows you to define fan-ins in a flexible yet concise way.<br/>
For example:

@@snip [-]($test/FanInSpec.scala) { #example1 }

When you call `attach(...)` on a @ref[Spout] you get back a DSL type which represents *several* open stream ports, not
just a single one as in the case of a simple @ref[Spout]. You can add more spouts to the mix by simply calling
`.attach(...)` again, as often as you like.

Once you have assembled all the spouts for the fan-in in this way simply call one of the defined `fanIn...` variants
to "close" the fan-in with the respective logic. Currently these fan-in variants are available:

* @ref[fanInConcat]
* @ref[fanInMerge]
* @ref[fanInRoundRobin]
* @ref[fanInSorted]
* @ref[fanInToTuple]
* @ref[fanInToHList]
* @ref[fanInToCoproduct]
* @ref[fanInToProduct]
* @ref[fanInToSum]

Additionally these fan-in shortcut transformations are defined:

* @ref[concat]
* @ref[interleave]
* @ref[nonEmptyOr]
* @ref[merge]
* @ref[mergeSorted]
* @ref[mergeToEither]
* @ref[zip]


Symmetric vs. Asymmetric Fan-Ins
--------------------------------

Some fan-in variants are "symmetric" in the sense that the order of the input streams doesn't matter.<br/>
@ref[fanInMerge] and @ref[fanInToSum] are probably the most used variants in that category.
 
For asymmetric fan-ins, like @ref[fanInConcat] or @ref[fanInToProduct], the order of the inputs is important.
To give you more flexibility for assembling fan-in inputs in the desired way *swave* also defines `attachLeft(...)`,
in addition to `attach(...)`. As you can probably guess `attachLeft` adds a new open spout to the *left* of the list
of open spouts.
 
Here is an example:

@@snip [-]($test/FanInSpec.scala) { #example2 }
 

Homogeneous vs. Heterogeneous Fan-Ins
-------------------------------------

The examples above show "homogeneous" fan-ins, in which all inputs are of the same type. With variants like
@ref[fanInConcat] or @ref[fanInMerge] this is the most common case.

However, @ref[fanInToTuple], @ref[fanInToProduct] or @ref[fanInToSum] are usually used on inputs of differing types,
i.e. as "heterogeneous" fan-ins. Here is an example:

@@snip [-]($test/FanInSpec.scala) { #example3 }

One thing you can also see in this example is that *swave* attempts to reduce all boilerplate to the absolute minimum.
Here the creation of case class instances from sub-streams for each member is implicitly taken care of.<br/>
*swave* builds on [shapeless] to make this kind of type-logic possible.


  [stream graph components]: ../basics.md#streams-as-graphs
  [Spout]: ../spouts.md
  [shapeless]: https://github.com/milessabin/shapeless
  [fanInConcat]: reference/fanInConcat.md
  [fanInRoundRobin]: reference/fanInRoundRobin.md
  [fanInMerge]: reference/fanInMerge.md
  [fanInSorted]: reference/fanInSorted.md
  [fanInToTuple]: reference/fanInToTuple.md
  [fanInToHList]: reference/fanInToHList.md
  [fanInToCoproduct]: reference/fanInToCoproduct.md
  [fanInToProduct]: reference/fanInToProduct.md
  [fanInToSum]: reference/fanInToSum.md
  [concat]: reference/concat.md
  [interleave]: reference/interleave.md
  [nonEmptyOr]: reference/nonEmptyOr.md
  [merge]: reference/merge.md
  [mergeSorted]: reference/mergeSorted.md
  [mergeToEither]: reference/mergeToEither.md
  [zip]: reference/zip.md