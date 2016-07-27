scalacOptions += "-deprecation"

addSbtPlugin("org.scalariform"    % "sbt-scalariform"   % "1.6.0")
addSbtPlugin("com.jsuereth"       % "sbt-pgp"           % "1.0.0")
addSbtPlugin("com.eed3si9n"       % "sbt-buildinfo"     % "0.5.0")
addSbtPlugin("de.heikoseeberger"  % "sbt-header"        % "1.5.1")
addSbtPlugin("com.github.gseitz"  % "sbt-release"       % "1.0.0")
addSbtPlugin("org.xerial.sbt"     % "sbt-sonatype"      % "1.1")
addSbtPlugin("org.scoverage"      % "sbt-scoverage"     % "1.3.5")
addSbtPlugin("org.scoverage"      % "sbt-coveralls"     % "1.1.0")