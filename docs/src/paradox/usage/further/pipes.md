Pipes
=====

A @scaladoc[Pipe] is a @ref[stream graph component] with one input port and one output port, i.e. it consumes elements
of type `A` and produces elements of type `B`.

@@@ p { .centered }
![A Pipe](.../pipe.svg)
@@@

As with all other stream graph components a pipe can internally consist of just one single stage or a whole graph
of stages that together form a structure that, to the outside, looks like a simple stage.


Creating Pipes
--------------

Most of the time you'll create pipes by appending transformations to a freshly created "identity Pipe", e.g. like this:

@@snip [-]($test/PipeSpec.scala) { #simplePipe }

Like all other streaming components pipes can be named, which is often used in *swave* itself to "package" several
transformations for nicer @ref[rendering]. For example, here is the essence of *swave's* @ref[slice] transformation:

@@snip [-]($test/PipeSpec.scala) { #slice }


Using Pipes
-----------

Pipes can be attached to @ref[Spouts] with the `via` operator: 

@@snip [-]($test/PipeSpec.scala) { #spout-via }

As you can probably guess the result of appending a `Pipe[A, B]` to a `Spout[A]` is a `Spout[B]`:

@@@ p { .centered }
![Pipe appended to Spout](.../spout-with-pipe.svg)
@@@

Pipes can also be prepended to @ref[Drains] with the `to` operator:  

@@snip [-]($test/PipeSpec.scala) { #pipe-to-drain }

The result of prepending a `Pipe[A, B]` to a `Drain[B]` is a `Drain[A]`:

@@@ p { .centered }
![Pipe prepended to Drain](.../pipe-with-drain.svg)
@@@

And, similarly to functions, pipes can be combined with other pipes to form larger compound ones:

@@snip [-]($test/PipeSpec.scala) { #pipe-to-pipe }

The result of combining a `Pipe[A, B]` and a `Pipe[B, C]` is a `Pipe[A, C]`:

@@@ p { .centered }
![Pipe combined with another Pipe](.../pipe-with-pipe.svg)
@@@


  [Pipe]: swave.core.Pipe
  [stream graph component]: ../basics.md#streams-as-graphs
  [rendering]: rendering.md
  [slice]: ../transformations/reference/slice.md
  [Spouts]: ../spouts.md
  [Drains]: ../drains.md