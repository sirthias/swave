Fibonacci Example
=================

This example shows how you can use a stream graph to model recursive algorithms like the generation of the stream of
all fibonacci numbers.

Here is a very simple way to create an infinite stream of all Fibonacci numbers that relies on an `unfold` @ref[Spout]:

@@snip [-]($test/FibonacciSpec.scala) { #unfold }

The recursion required for the stream generation is hereby provided by the "unfolding" feature.<br/>
As such it is "built-in" and not that interesting.
  
A maybe more illustrative way to construct the same stream is the following one, which makes the recursion explicit in
the stream graph structure:

@@snip [-]($test/FibonacciSpec.scala) { #cycle }

The stream graph defined by this code can be visualized like this:

... TODO ...

  [Spout]: ../spouts.md