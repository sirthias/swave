import ReleaseTransformations._
import com.lightbend.paradox.ParadoxProcessor

lazy val contributors = Seq(
  "Mathias Doenitz" -> "sirthias")

lazy val commonSettings = Seq( // reformatOnCompileSettings ++ Seq(
  organization := "io.swave",
  scalaVersion := "2.12.1",
  crossScalaVersions := Seq("2.12.1", "2.11.8"),
  homepage := Some(url("http://swave.io")),
  description := "A Reactive Streams infrastructure implementation in Scala",
  startYear := Some(2016),
  licenses := Seq("MPL 2.0" -> new URL("https://www.mozilla.org/en-US/MPL/2.0/")),
  javacOptions ++= commonJavacOptions,
  scalacOptions ++= commonScalacOptions,
  scalacOptions in Test ~= (_ filterNot (_ == "-Ywarn-value-discard")),
  scalacOptions in (Test, console) ~= { _ filterNot { o => o == "-Ywarn-unused-import" || o == "-Xfatal-warnings" } },
  scalacOptions in (Compile, doc) ~= { _.filterNot(o => o == "-Xlint" || o == "-Xfatal-warnings") :+ "-nowarn" },
  initialCommands in console := """import swave.core._""",
  scmInfo := Some(ScmInfo(url("https://github.com/sirthias/swave"), "scm:git:git@github.com:sirthias/swave.git")),

  // code formatting
  headers := Map("scala" -> de.heikoseeberger.sbtheader.license.MPLv2_NoCopyright("", "")),
  // scalafmtConfig := Some(file(".scalafmt.conf")),
  // formatSbtFiles := false,

  // test coverage
  coverageMinimum := 90,
  coverageFailOnMinimum := false,
  coverageExcludedPackages := """swave\.benchmarks\..*;swave\.examples\..*""")

// lazy val noScalaFmtFormatting = includeFilter in hasScalafmt := NothingFilter

lazy val publishingSettings = Seq(
  useGpg := true,
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  publishTo := Some {
    val nexus = "https://oss.sonatype.org/"
    if (version.value.trim endsWith "SNAPSHOT") "snapshots" at nexus + "content/repositories/snapshots"
    else "releases" at nexus + "service/local/staging/deploy/maven2"
  },
  pomExtra :=
    <developers>
      {for ((name, username) <- contributors)
        yield <developer><id>{username}</id><name>{name}</name><url>http://github.com/{username}</url></developer>
      }
    </developers>)

lazy val noPublishingSettings = Seq(
  publish := (),
  publishLocal := (),
  publishArtifact := false)

lazy val releaseSettings = {
  val runCompile = ReleaseStep(
    action = { st: State =>
      val extracted = Project.extract(st)
      val ref = extracted.get(thisProjectRef)
      extracted.runAggregated(compile in Compile in ref, st)
    })

  Seq(
    releaseCrossBuild := false,
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runCompile,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishArtifacts,
      setNextVersion,
      commitNextVersion,
      releaseStepCommand("sonatypeReleaseAll"),
      pushChanges))
}

lazy val commonJavacOptions = Seq(
  "-encoding", "UTF-8",
  "-Xlint:unchecked",
  "-Xlint:deprecation")

lazy val commonScalacOptions = Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-language:_",
  "-unchecked",
  "-Xfatal-warnings",
  "-Xlint",
  "-Xfuture",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-inaccessible",
  "-Ywarn-infer-any",
  "-Ywarn-nullary-override",
  "-Ywarn-nullary-unit",
  "-Ywarn-numeric-widen",
  "-Ywarn-unused-import",
  "-Ywarn-value-discard")

lazy val macroParadise =
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)

/////////////////////// DEPENDENCIES /////////////////////////

// core
val `reactive-streams`     = "org.reactivestreams"        %   "reactive-streams"      % "1.0.0"
val `jctools-core`         = "org.jctools"                %   "jctools-core"          % "2.0"
val `typesafe-config`      = "com.typesafe"               %   "config"                % "1.3.1"
val shapeless              = "com.chuusai"                %%  "shapeless"             % "2.3.2"
val `scala-logging`        = "com.typesafe.scala-logging" %%  "scala-logging"         % "3.5.0"

// *-compat
val `akka-stream`          = "com.typesafe.akka"          %%  "akka-stream"           % "2.4.14"
val `scodec-bits`          = "org.scodec"                 %%  "scodec-bits"           % "1.1.2"

// test
val scalatest              = "org.scalatest"              %%  "scalatest"             % "3.0.1"   % "test"
val `reactive-streams-tck` = "org.reactivestreams"        %   "reactive-streams-tck"  % "1.0.0"   % "test"

// examples
val `akka-http-core`       = "com.typesafe.akka"          %%  "akka-http-core"        % "10.0.0"
val logback                = "ch.qos.logback"             %   "logback-classic"       % "1.1.8"

/////////////////////// PROJECTS /////////////////////////

lazy val swave = project.in(file("."))
  .aggregate(benchmarks, `compat-akka`, `compat-scodec`, core, `core-macros`, `core-tests`, docs, examples, testkit)
  .settings(commonSettings: _*)
  .settings(releaseSettings: _*)
  .settings(noPublishingSettings: _*)

lazy val benchmarks = project
  .dependsOn(core)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(noPublishingSettings: _*)

lazy val `compat-akka` = project
  .dependsOn(core, `core-macros` % "compile-internal", testkit)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(releaseSettings: _*)
  .settings(publishingSettings: _*)
  .settings(
    moduleName := "swave-akka-compat",
    libraryDependencies ++= Seq(`akka-stream`, scalatest))

lazy val `compat-scodec` = project
  .dependsOn(core, `core-macros` % "compile-internal", testkit, `core-tests` % "test->test")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(releaseSettings: _*)
  .settings(publishingSettings: _*)
  .settings(
    moduleName := "swave-scodec-compat",
    libraryDependencies ++= Seq(`scodec-bits`, scalatest, logback % "test"))

lazy val core = project
  .dependsOn(`core-macros` % "compile-internal, test-internal")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(releaseSettings: _*)
  .settings(publishingSettings: _*)
  .settings(
    moduleName := "swave-core",
    macroParadise,
    libraryDependencies ++= Seq(`reactive-streams`,  `jctools-core`, `typesafe-config`, shapeless, `scala-logging`,
      scalatest))

lazy val `core-macros` = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(noPublishingSettings: _*)
  .settings(
    macroParadise,
    libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value)

lazy val `core-tests` = project
  .dependsOn(core % "test->test", testkit, `core-macros` % "test-internal")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(noPublishingSettings: _*)
  .settings(
    // noScalaFmtFormatting,
    macroParadise,
    libraryDependencies ++= Seq(shapeless, scalatest, `reactive-streams-tck`, logback % "test"))

lazy val docs = project
  .dependsOn(`compat-akka`, `compat-scodec`, core, testkit)
  .enablePlugins(ParadoxSitePlugin)
  .settings(commonSettings: _*)
  .settings(noPublishingSettings: _*)
  .settings(ghpages.settings)
  .settings(
    // noScalaFmtFormatting,
    git.remoteRepo := scmInfo.value.get.connection.drop("scm:git:".length),
    libraryDependencies ++= Seq(shapeless, scalatest),
    apiURL := Some(url("http://swave.io/api/")),
    siteSubdirName in Paradox := "",
    paradoxTheme := None,
    sourceDirectory in (Paradox, paradoxTheme) := sourceDirectory.value / "paradox" / "_template",
    paradoxProcessor in Paradox := new ParadoxProcessor(writer = new CustomWriter),
    paradoxNavigationDepth := 3,
    commands += Command.command("openSite") { state =>
      val uri = s"file://${Project.extract(state).get(target in Paradox)}/index.html"
      state.log.info(s"Opening browser at $uri ...")
      java.awt.Desktop.getDesktop.browse(new java.net.URI(uri))
      state
    },
    paradoxProperties in Paradox ++= Map(
      "latest-version" -> "0.5.0",
      "scala.binaryVersion" -> scalaBinaryVersion.value,
      "scala.version" -> scalaVersion.value,
      "github.base_url" -> {
        val v = version.value
        s"https://github.com/sirthias/swave/tree/${if (v.endsWith("SNAPSHOT")) "master" else "v" + v}"
      },
      "extref.rfc.base_url" -> "http://tools.ietf.org/html/rfc%s",
      "extref.akka.base_url" -> "http://doc.akka.io/docs/akka/2.4/scala/%s.html",
      "snip.test.base_dir" -> s"${(sourceDirectory in Test).value}/scala/swave/docs",
      "snip.core.base_dir" -> s"${(sourceDirectory in Compile in core).value}/scala/swave/core",
      "image.base_url" -> ".../assets/img",
      "scaladoc.org.reactivestreams.base_url" -> "http://www.reactive-streams.org/reactive-streams-1.0.0-javadoc/",
      "scaladoc.akka.base_url" -> "http://doc.akka.io/api/akka/2.4/",
      "scaladoc.scodec.bits.base_url" -> "http://scodec.org/api/scodec-bits/1.1.2/",
      "scaladoc.swave.compat.akka.base_url" -> "http://swave.io/api/compat-akka/latest/",
      "scaladoc.swave.compat.scodec.base_url" -> "http://swave.io/api/compat-scodec/latest/",
      "scaladoc.swave.core.base_url" -> "http://swave.io/api/core/latest/",
      "scaladoc.swave.testkit.base_url" -> "http://swave.io/api/testkit/latest/"))
  .settings({
    def apiDocs(p: Project) =
      siteMappings ++= {
        val n = (name in p).value
        for ((f, d) <- (mappings in (p, Compile, packageDoc)).value)
          yield f -> s"api/$n/latest/$d"
      }
    List(apiDocs(`compat-akka`), apiDocs(core), apiDocs(`compat-scodec`), apiDocs(testkit))}: _*)

lazy val examples = project
  .dependsOn(core, `compat-akka`)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(noPublishingSettings: _*)
  .settings(
    // noScalaFmtFormatting,
    fork in run := true,
    connectInput in run := true,
    javaOptions in run ++= Seq("-XX:+UnlockCommercialFeatures", "-XX:+FlightRecorder"),
    libraryDependencies ++= Seq(`akka-stream`, `akka-http-core`, logback))

lazy val testkit = project
  .dependsOn(core, `core-macros` % "compile-internal")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(releaseSettings: _*)
  .settings(publishingSettings: _*)
  .settings(
    moduleName := "swave-testkit",
    macroParadise)
