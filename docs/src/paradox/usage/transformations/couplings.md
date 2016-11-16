Couplings
=========

@scaladoc[Couplings] are @ref[stream graph components] with one input, one output and no internal transformation logic.
They are typically used to manually connect two ports of a stream graph that cannot be otherwise connected via the
streaming DSL, e.g. for creating cycles.

@@@ p { .centered }
![A Coupling](.../coupling.svg)
@@@

A `Coupling` is essentially nothing but a @ref[Drain] and @ref[Spout] of the same element type that are directly
connected to each other internally. You can use the two sides of the `Coupling` independently at arbitrary points
in a stream graph definition to create connections where you need them.

Here is an example of a stream that contains a cycle and therefore requires the use of a `Coupling`:
 
@@snip [-]($test/CouplingSpec.scala) { #fibonacci } 

  [Couplings]: swave.core.Coupling
  [stream graph components]: ../basics.md#streams-as-graphs
  [Spout]: ../spouts.md
  [Drain]: ../drains.md