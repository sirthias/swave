Monte Carlo Pi Example
======================

This example is a slightly adapted version of the one that has been shown in a few @ref[talks on swave] in the past.<br/>
It implements a simple approximation of Pi via the Monte Carlo method, whose basic idea can be illustrated like this:

@@@ p { .centered }
![Approximating Pi with the Monte Carlo method](.../pi.svg)
@@@

If we'd shoot holes through this unit square at random points the share of the holes that are located in the grey
area would asymptotically approach π/4 as we increase the number of shots.
 
There are many ways in which a simulation of this method can be implemented in code.<br/>
Here is a stream-based solution, which is intentionally somewhat convoluted to show off a few of *swave's* features:

@@snip [-]($test/MonteCarloPi.scala) { #example }

When this code is run it generates 50 mio samples, at maximum speed, to approximate π using the Monte Carlo method.
After each 1 mio samples one line of progress information is printed to console.  

The streams setup contains elements from all five basic groups of stream transformations: @ref[simple transformations],
@ref[fan-outs], @ref[fan-ins], @ref[injects] and @ref[flattenings].
The implemented stream graph can be visualized like this. 

@@@ p { .centered }
![Monte Carlo Pi Stream Graph](.../pi-stream.svg)
@@@

One thing that's interesting about this example is that everything is running synchronously (yet without any blocking)
on the caller thread. There are no stages in the graph that require asynchronous dispatch and thus *swave* will run
it entirely without moving execution to another thread, which, in simple and "small" cases like this one, is often the
fastest way to run a stream.
 

Rate Detaching
--------------

To demonstrate another feature called "rate detaching" let's transform the example slightly.
So far we've printed a line of status information after every 1 mio samples. While this probably works ok on your
machine it might end up generating too much output on really fast machines and too little if the machine is really slow.

We can fix this by letting the stream produce a status update *once a second*, i.e. independently of the number of
generated samples:

@@snip [-]($test/MonteCarloPiThrottled.scala) { #example }

Up to the @ref[scan] stage everything has stayed as before. But rather than then simply filtering for every million-th
element we use @ref[conflateToLast], which is one frequently used incarnation of the more general @ref[conflate].
@ref[conflate], together with its sibling @ref[expand], are the two basic variants of transformations that perform
"rate detaching": They decouple their downstream's request rate from their upstream's production rate.
 
In our case we want the upstream to run at full speed, dropping all incoming elements that the downstream cannot digest.
Whenever the downstream requests one element we want it to receive the most current element from upstream.
This is exactly what @ref[conflateToLast] does.
 
Apart from the difference in how status update lines are printed there is another important difference between this
version and the one above: The stream now runs asynchronously off the caller thread. This is caused by the
@ref[throttle] transformation, which must react to asynchronous timer signals and thus cannot run purely on the
caller thread.

  [talks on swave]: ../../introduction/talks-on-swave.md
  [simple transformations]: ../transformations/simple.md
  [fan-outs]: ../transformations/fan-outs.md
  [fan-ins]: ../transformations/fan-ins.md
  [injects]: ../transformations/streams-of-streams.md#injecting-transformations
  [flattenings]: ../transformations/streams-of-streams.md#flattening-transformations
  [scan]: ../transformations/reference/scan.md
  [conflateToLast]: ../transformations/reference/conflateToLast.md
  [conflate]: ../transformations/reference/conflate.md
  [expand]: ../transformations/reference/expand.md
  [throttle]: ../transformations/reference/throttle.md