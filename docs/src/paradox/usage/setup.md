Setup
=====

Modules
-------

Currently *swave* provides the following modules:

swave-core
: The core infrastructure you'll want to add as a dependency on in almost all cases.

@ref:[swave-akka-compat](swave-akka-compat/index.md)
: Helpers for seamless integration with [Akka-Stream]

@ref:[swave-scodec-compat](swave-scodec-compat/index.md)
: Helpers for seamless integration with [Scodec]

@ref:[swave-testkit](swave-testkit/index.md)
: Testkit for testing *swave* streams


Using *swave* with [SBT]
------------------------

*swave* is currently built for Scala 2.11 only.

This is how you add the modules as dependencies to your [SBT] build:

@@@vars 
```scala
libraryDependencies ++= Seq(
  "io.swave" %% "swave-core"          % "$latest-version$",
  "io.swave" %% "swave-akka-compat"   % "$latest-version$", // if required
  "io.swave" %% "swave-scodec-compat" % "$latest-version$", // if required
  "io.swave" %% "swave-testkit"       % "$latest-version$"  // if required
)
```
@@@


Dependencies
------------

*swave-core* has the following dependencies that it will transitively pull into your classpath:

- `org.reactivestreams % reactive-streams` (non-scala, see @ref:[here](../introduction/reactive-streams.md#the-artifact) for more info)
- `org.jctools % jctools-core` (non-scala)
- `com.typesafe % config` (non-scala)
- `com.chuusai %% shapeless`
- `com.typesafe.scala-logging %% scala-logging`
