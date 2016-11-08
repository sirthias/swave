Contributing
============

We value all kinds of contributions, not just actual code. Maybe the easiest and yet very good way
of helping us improve *swave* is to ask questions, voice concerns or propose improvements on the
@ref[Mailing List](../support.md#mailing-list).

Or simply tell us about you or your organization using *swave* by sending us a small statement for the
@ref[References](references.md) page.

If you do like to contribute actual code in the form of bug fixes, new features or other patches this page gives
you more info on how to do it.


Cooperation Process
-------------------

*swave* follows the [Collective Code Construction Contract (C4)][1], which is an evolution of the github.com Fork + Pull
Model, aimed at providing an optimal collaboration model for free software projects.

This process was originally designed by the late [@hintjens][2] for the [ZeroMQ][3] community where it has worked
exceptionally well for many years. It has these goals (quote from [C4 specification][1]):

> 1. To maximize the scale and diversity of the community around a project, by reducing the friction for new Contributors and creating a scaled participation model with strong positive feedbacks;
>
> 2. To relieve dependencies on key individuals by separating different skill sets so that there is a larger pool of competence in any required domain;
>
> 3. To allow the project to develop faster and more accurately, by increasing the diversity of the decision making process;
>
> 4. To support the natural life cycle of project versions from experimental through to stable, by allowing safe experimentation, rapid failure, and isolation of stable code;
>
> 5. To reduce the internal complexity of project repositories, thus making it easier for Contributors to participate and reducing the scope for error;
>
> 6. To enforce collective ownership of the project, which increases economic incentive to Contributors and reduces the risk of hijack by hostile entities.

If you'd like to participate in the development of *swave* you are very much invited to check out the
[C4 specification][1] as well as the [background information available here][4].
We are looking forward to receiving your first pull request!

  [1]: http://rfc.zeromq.org/spec:42/C4/
  [2]: http://hintjens.com/
  [3]: http://zeromq.org/
  [4]: http://zguide.zeromq.org/page:chapter6#The-ZeroMQ-Process-C


Building *swave*
----------------

Since *swave* is open-source and hosted on github you can easily build it yourself.

Here is how:

1. Install [SBT](http://www.scala-sbt.org/).
   You can see the SBT version that *swave* is currently built with @github[here](/project/build.properties).
   
2. Check out the *swave* source code from the [github repository].
   Pick the `master` branch for the most current state or a tagged commit for a specific version.
   
3. Run `sbt compile test` to compile the suite and run all tests.


Contributing/Fixing Documentation
---------------------------------

The site, i.e. what you see here at http://swave.io, is built with [paradox], a static site generator.
It converts the markdown documentation living in the @github[docs sub-project](/docs/) into static HTML.
If you want to contribute documentation (which we always welcome very much!) try this process for quickly seeing
how your changes affect the look of the site: 

* Follow the instructions in the above section on "Building *swave*"

* Start `sbt`

* In the sbt shell run `project docs` to change into the `docs` project

* Run `makeSite` to build the site for the first time (which might take some time)

* Run `openSite` to open a browser on the `index.html` file of the directory the site was produced to
 
* Run `~makeSite` to let SBT monitor changes to the documentation sources and rebuild the site automatically
 
* Edit the documentation files inside the `docs/src/paradox` subdirectory.
  After saving a file SBT will automatically regenerate the site and you can reload the page in the browser
  to see your changes.
  
  [paradox]: https://github.com/lightbend/paradox


Issue Tracking
--------------

The *swave* team uses the [Issues Page] of the projects [github repository] for issue management.
If you find a bug and would like to report it please go there and create an issue.

If you are unsure, whether the problem you've found really is a bug please ask on the
@ref[Mailing List](../support.md) first.

  [Issues Page]: https://github.com/sirthias/swave/issues
  [github repository]: https://github.com/sirthias/swave/  
  
  
GIT Branching Model
-------------------

The *swave* team follows the "standard" practice of using the `master` branch as main integration branch,
with WIP- and feature branches branching of it. The rule is to keep the `master` branch always "in good shape",
i.e. having it compile and test cleanly.

Additionally we might maintain release branches for older and possibly future releases.


GIT Commit Messages
-------------------

We try to follow the [imperative present tense style for commit messages][style] and additionally prefix each message
with some simple meta data that make it easier to see

- how the public API is affected by the commit
- what sub-project(s) the commit mainly affects
- which ticket the commit is associated with

Here are three exemplary (and made-up) commit messages to illustrate the concept:

```nohighlight
=tsk #123 fix incorrect display of unterminated output state

+cor #234 add `takeWithin` transformation

!cak,csc #345 clean up package structure
```

Following this naming pattern for *all* commits (except for merges, which start with "Merge") is extremely helpful when
looking at the commit history. Also, it makes the generation of CHANGELOG entries and release notes much easier.

  [style]: http://tbaggery.com/2008/04/19/a-note-about-git-commit-messages.html


### API Effect Category  
  
The first character classifies the effect of the commit on the public *swave* API that we try to keep as stable as
possible. Requiring this marker makes sure that the committer has actively thought about the effects of the commit on
the public API. There are three categories:

|Marker|Category |Description                                                                    
|:----:|---------|-----------
| `=`  |Neutral  |Only touches things "under the hood" and has no effect on *swave's* public API.
| `+`  |Extending|Extends the API by adding things. In rare cases this might break code due to things like identifier shadowing but is generally considered a "safe" change.
| `!`  |Breaking |Changes or removes public API elements. Will definitely break user code relying on these parts of the API surface.

Note that apart from the actual Scala interfaces the public API surface covered by these categories also includes
configuration settings (most importantly the `reference.conf` files).


### Project Identifier(s)
  
Immediately after the initial "API Effect Category Marker" we list all sub-projects touched by the commit (in decreasing
order of "affectedness" and separated by a simple comma without spaces). In order to keep things concise the projects
are identified with these simple 3-letter abbreviations:
  
| Sub-Project |Abbreviation|
|-------------|:----------:|
|core         |   `cor`    |
|core-macros  |   `cor`    |
|core-tests   |   `cor`    |
|compat-akka  |   `cak`    |
|compat-scodec|   `csc`    |
|docs         |   `doc`    |
|examples     |   `exa`    |
|testkit      |   `tkt`    |
|benchmarks   |   `bhm`    |
|sbt project  |   `pro`    |


### Ticket Number

The [Collective Code Construction Contract (C4)][1] mandates that all changes should happen in the context of a certain
ticket / github issue. By putting the number of the ticket into the commit message GitHub can associate the commit with
the ticket and list it on the ticket's GitHub page.

Also, having all commits display their ticket number makes it much easier to understand the project history and compile
the release notes and the CHANGELOG.