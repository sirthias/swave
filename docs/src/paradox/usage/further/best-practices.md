Best Practices
==============

In this chapter we collect general recommendations that have proven to be helpful when working with backpressured
streams in general as well as *swave* in particular.


Prefer `def` over `val`
----------------------- 

One simple way to deal with the non-reusability of *swave's* stream components is to model them as a `def` rather than
a `val` wherever reuse is desired, e.g. like this:

@@snip [-]($test/BasicSpec.scala) { #reuse }

Apart from ensuring that you'll never see an @scaladoc[IllegalReuseException] it also has the benefit that
parameterizing your higher-level stream constructs becomes trivial (as all that's required is giving the `def` a
parameter list).


  [IllegalReuseException]: swave.core.IllegalReuseException