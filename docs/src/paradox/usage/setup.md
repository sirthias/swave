Setup
=====

Modules
-------

Currently *swave* provides the following modules:

swave-core
: The core infrastructure you'll want to add as a dependency on in almost all cases.

@ref[swave-akka-compat](swave-akka-compat/index.md)
: Helpers for seamless integration with @extref[Akka-Stream]

@ref[swave-scodec-compat](swave-scodec-compat/index.md)
: Helpers for seamless integration with [Scodec]

@ref[swave-testkit](swave-testkit/index.md)
: Testkit for testing *swave* streams


Using *swave* with [SBT]
------------------------

*swave* is available for Scala 2.11 and 2.12.

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

- `org.reactivestreams % reactive-streams` (non-scala, see @ref[here][rs] for more info)
- `org.jctools % jctools-core` (non-scala, [github project here][jctools])
- `com.typesafe % config` (non-scala, [github project here][typesafe config])
- `com.chuusai %% shapeless` ([github project here][shapeless])
- `com.typesafe.scala-logging %% scala-logging` ([github project here][scala-logging])

  [Akka-Stream]: akka:stream/index
  [Scodec]: http://scodec.org/
  [SBT]: http://www.scala-sbt.org/
  [rs]: ../introduction/reactive-streams.md#the-artifact
  [jctools]: https://github.com/JCTools/JCTools
  [typesafe config]: https://github.com/typesafehub/config
  [shapeless]: https://github.com/milessabin/shapeless
  [scala-logging]: https://github.com/typesafehub/scala-logging
  