Debugging
=========

Easy debugging is crucial for everyone writing stream-based logic, not only for users that are new to the field.
Unfortunately, finding and fixing problems in @ref[Reactive Streams]-based logic can be tricky at times, because in
addition to the data channel (going downstream) there is also always the demand channel (going upstream) to pay
attention to.
 
*swave* gives you several tools that help with debugging a stream setup that doesn't do what you expect it to,
the most important ones being logging and @ref[rendering].


Basic Process
-------------

If you don't quite understand why a certain stream setup is behaving the way it is the first things you should do are
probably these:

Reproduce
: If the problem only appears sporadically you'll have to play around with all contributing or environmental factors in
order to reliably reproduce the problem.
 
Simplify
: Try to remove all elements from the stream graph that don't appear to be contributing to problem. Time spent here can
easily pay off multiple times later.
   
Add Logging
: Next you should insert logging transformations at strategic points in your stream graph. Once you understand *what*
exactly is happening the question *why* it happens and how you can change it often answers itself. 
   

Logging Stream Execution
------------------------

As in many cases the simplest tools are often the best ones and debugging stream-based logic is no exception.
Temporarily adding logging transformations to trace stream execution can be an excellent and quite efficient way to
diagnose issues.

*swave* gives you a number of "identity" transformations that perform logging of various stream execution aspects
without affecting stream semantics in any way:

- @ref[logEvent]
- @ref[onCancel]
- @ref[onComplete]
- @ref[onElement]
- @ref[onError]
- @ref[onRequest]
- @ref[onSignal]
- @ref[onSignalPF]
- @ref[onStart]
- @ref[onTerminate]

The most frequently used one is probably @ref[logEvent], which outputs an informational `String` for every signal
that is passing through the stage. By default these strings are `println`ed to the console but you can easily
reroute them to any kind of logging facility.

In order to illustrate its benefit let's look at this example:

@@snip [-]($test/Debugging.scala) { #example }

As you can see, @ref[logEvent] lets you specify a "marker" string that makes it easy to associate the output of the
particular stage with its position in the graph.

When we run this stream the following output is produced to the console:

```nohighlight
B: ⇠ Request(32)
A: ⇠ Request(5)
A: ⇢ OnNext(0)
A: ⇢ OnNext(1)
A: ⇢ OnNext(2)
A: ⇢ OnNext(3)
A: ⇢ OnNext(4)
A: ⇠ Cancel
B: ⇢ OnNext(20)
B: ⇢ OnComplete
```

As you can see we can get a very good impression of how this stream runs just from this log.

(You might be asking yourself, where the initial `Request(32)` comes from here, as there is nothing in the stream setup
that hints towards this particular number. The `32` corresponds to the @ref[configured] `max-batch-size` that larger
demand is always broken down to. In this example the `.drainToBlackHole()` at the end signals infinite demand that
is "spoon-fed" to the stream in chunks of `max-batch-size`.)


  [Reactive Streams]: ../../introduction/reactive-streams.md
  [rendering]: rendering.md
  [logEvent]: ../transformations/reference/logEvent.md
  [onCancel]: ../transformations/reference/onCancel.md
  [onComplete]: ../transformations/reference/onComplete.md
  [onElement]: ../transformations/reference/onElement.md
  [onError]: ../transformations/reference/onError.md
  [onRequest]: ../transformations/reference/onRequest.md
  [onSignal]: ../transformations/reference/onSignal.md
  [onSignalPF]: ../transformations/reference/onSignalPF.md
  [onStart]: ../transformations/reference/onStart.md
  [onTerminate]: ../transformations/reference/onTerminate.md
  [configured]: configuration.md