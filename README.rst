swave.io
========

**swave** is a lightweight Reactive-Streams_ infrastructure toolkit for Scala.
It attempts to provide all the general-purpose stream construction, stream consumption and stream transformation logic
that is typically required in applications that work with Reactive-Streams_.

.. image:: https://img.shields.io/maven-central/v/io.swave/swave-core_2.11.svg
   :target: https://maven-badges.herokuapp.com/maven-central/io.swave/swave-core_2.11
   :alt: The latest swave artifacts on Maven Central

.. image:: https://img.shields.io/badge/mailing%20list-active-brightgreen.svg
   :target: https://groups.google.com/forum/#!forum/swave-user
   :alt: Mailing list for swave users

.. image:: https://img.shields.io/gitter/room/nwjs/nw.js.svg?maxAge=86400
   :target: https://gitter.im/sirthias/swave
   :alt: Gitter chat for swave https://gitter.im/sirthias/swave

.. image:: https://img.shields.io/badge/uses-badges-ff69b4.svg
   :target: http://shields.io/
   :alt: uses badges

.. _Reactive-Streams: http://reactive-streams.org/

----

.. contents:: Contents of this Document


Getting Started
---------------

Installation
~~~~~~~~~~~~

The library artifacts for *swave* live on `Maven Central`_ and can be tied into your SBT-based Scala project like this:

.. code:: Scala

    libraryDependencies += "io.swave" %% "swave-core" % "0.5-M3"

The latest released version is **0.5-M3**. Currently it is available only for Scala 2.11.

*swave-core* has the following dependencies that it will transitively pull into your classpath:

- org.reactivestreams % reactive-streams (non-scala)

- org.jctools % jctools-core (non-scala)

- com.typesafe % config (non-scala)

- com.chuusai %% shapeless

- com.typesafe.scala-logging %% scala-logging

Once *swave* is on your classpath you can use this single import to bring everything you need into scope:

.. code:: Scala

    import swave.core._

.. _Maven Central: http://search.maven.org/
.. _shapeless: https://github.com/milessabin/shapeless


Basic Usage
~~~~~~~~~~~

Providing users and contributors with proper, in-depth documentation for all features is our next number one priority
(also see https://github.com/sirthias/swave/issues/2 ).

Up until this goal is reached here are the most important entry points to the *swave* API:

- The three core types you'll work with most are

  - ``Spout[T]``: An outlet of stream elements, similar to ``akka-stream``'s ``Source``
  - ``Pipe[A, B]``: A piece of stream pipeline with two open ends, similar to ``akka-stream``'s ``Flow``
  - ``Drain[T, R]``: An inlet for stream elements, similar to ``akka-stream``'s ``Sink``

- You create instances of the core types mostly via the methods on their companion objects.

- Most stream operations are defined by the ``swave.core.StreamOps`` trait.

- ``Spout``, ``Pipe``, ``Drain`` instances are not reusable. After creation they can only be started once.
  If you'd like to run a stream again you need to need to recreate it.

- In order to be able to start a stream you need an implicit ``swave.core.StreamEnv`` instance in scope.
  A ``StreamEnv`` instance encapsulates everything required to manage a larger streaming setup
  (like configuration, dispatchers, etc.).


Resources
---------

Talk: "swave - Reactive Streams in Scala" presented at `ScalaDays 2016 @ Berlin`_ (Jun 2016)
  | Slides: http://swave.io/scaladays/2016/
  | Video: https://youtu.be/htwmROeki0c

Talk: "swave - A Preview" presented at SCALAR_ (Apr 2016)
  | Slides: http://swave.io/scalar/
  | Video: https://youtu.be/0jq0-Ph2gpM

.. _ScalaDays 2016 @ Berlin: http://event.scaladays.org/scaladays-berlin-2016
.. _SCALAR: http://scalar-conf.com/


Participating
-------------

*swave* follows the `Collective Code Construction Contract (C4)`_, which is an evolution of the github.com Fork + Pull
Model, aimed at providing an optimal collaboration model for free software projects.

This process was originally designed by `@hintjens`_ for the `ZeroMQ`_ community where it has worked exceptionally well
for many years. It has these goals (quote from `C4 specification`_):

    1. To maximize the scale and diversity of the community around a project, by reducing the friction for new Contributors and creating a scaled participation model with strong positive feedbacks;

    2. To relieve dependencies on key individuals by separating different skill sets so that there is a larger pool of competence in any required domain;

    3. To allow the project to develop faster and more accurately, by increasing the diversity of the decision making process;

    4. To support the natural life cycle of project versions from experimental through to stable, by allowing safe experimentation, rapid failure, and isolation of stable code;

    5. To reduce the internal complexity of project repositories, thus making it easier for Contributors to participate and reducing the scope for error;

    6. To enforce collective ownership of the project, which increases economic incentive to Contributors and reduces the risk of hijack by hostile entities.

If you'd like to participate in the development of *swave* you are very much invited to check out the
`C4 specification`_ as well as the `background information available here`__.
We are looking forward to receiving your first pull request!

.. _ZeroMQ: http://zeromq.org/
.. _C4 specification: http://rfc.zeromq.org/spec:42/C4/
.. _Collective Code Construction Contract (C4): `C4 specification`_
.. _@hintjens: https://github.com/hintjens
__ http://zguide.zeromq.org/page:chapter6#The-ZeroMQ-Process-C


License
-------

*swave* is released under the `MPL 2.0`_, which is a simple and modern weak `copyleft`_ license.

Here is the gist of the terms that are likely most important to you (disclaimer: the following points are not legally
binding, only the license text itself is):

If you'd like to use *swave* as a library in your own applications:

- **swave is safe for use in closed-source applications.**
  The MPL share-alike terms do not apply to applications built on top of or with the help of *swave*.

- **You do not need a commercial license.**
  The MPL applies to *swave's* own source code, not your applications.

If you'd like to contribute to *swave*:

- You do not have to transfer any copyright.

- You do not have to sign a CLA.

- You can be sure that your contribution will always remain available in open-source form and
  will not *become* a closed-source commercial product (even though it might be *used* by such products!)

For more background info on the license please also see the `official MPL 2.0 FAQ`_.

.. _MPL 2.0: https://www.mozilla.org/en-US/MPL/2.0/
.. _copyleft: http://en.wikipedia.org/wiki/Copyleft
.. _official MPL 2.0 FAQ: https://www.mozilla.org/en-US/MPL/2.0/FAQ/
