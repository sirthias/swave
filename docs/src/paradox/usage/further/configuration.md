Configuration
=============

*swave* relies on [Typesafe Config] for configuration of all aspects of the streaming environment.
When you create a @scaladoc[StreamEnv] instance for your application the configuration is loaded from the classpath
(and/or other sources, like JVM system properties) and used for all stream runs under this `StreamEnv`.
 
This is the content of *swave's* own `reference.conf` which contains the default values for all defined config settings:

@@snip [-]($res/reference.conf) { #source-quote type=bash }

  [Typesafe Config]: https://github.com/typesafehub/config
  [StreamEnv]: swave.core.StreamEnv