import scalariform.formatter.preferences._
import de.heikoseeberger.sbtheader.HeaderPattern
import com.typesafe.sbt.SbtScalariform._
import ReleaseTransformations._

lazy val contributors = Seq(
  "Mathias Doenitz" -> "sirthias")

lazy val commonSettings = Seq(
  organization := "io.swave",
  scalaVersion := "2.11.8",
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
  headers := Map("scala" -> (
    HeaderPattern.cStyleBlockComment,
    """/* This Source Code Form is subject to the terms of the Mozilla Public
      | * License, v. 2.0. If a copy of the MPL was not distributed with this
      | * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
      |
      |""".stripMargin)),
  ScalariformKeys.preferences := ScalariformKeys.preferences.value
    .setPreference(AlignParameters, false)
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(DanglingCloseParenthesis, Prevent)
    .setPreference(DoubleIndentClassDeclaration, true)
    .setPreference(PreserveSpaceBeforeArguments, true)
    .setPreference(RewriteArrowSymbols, true))

lazy val publishingSettings = Seq(
  useGpg := true,
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  publishTo <<= version { v: String =>
    val nexus = "https://oss.sonatype.org/"
    if (v.trim endsWith "SNAPSHOT") Some("snapshots" at nexus + "content/repositories/snapshots")
    else                            Some("releases" at nexus + "service/local/staging/deploy/maven2")
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
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:experimental.macros",
  "-unchecked",
  "-Xfatal-warnings",
  "-Xlint",
  "-Yinline-warnings",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Ywarn-unused-import",
  "-Xfuture")

lazy val macroParadise =
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)

/////////////////////// DEPENDENCIES /////////////////////////

// core
val `reactive-streams`     = "org.reactivestreams"        %   "reactive-streams"      % "1.0.0"
val `jctools-core`         = "org.jctools"                %   "jctools-core"          % "1.2.1"
val `typesafe-config`      = "com.typesafe"               %   "config"                % "1.3.0"
val shapeless              = "com.chuusai"                %%  "shapeless"             % "2.3.1"
val `scala-logging`        = "com.typesafe.scala-logging" %%  "scala-logging"         % "3.4.0"

// *-compat
val `akka-stream`          = "com.typesafe.akka"          %%  "akka-stream"           % "2.4.8"
val `scodec-bits`          = "org.scodec"                 %%  "scodec-bits"           % "1.1.0"

// test
val scalatest              = "org.scalatest"              %%  "scalatest"             % "2.2.6"   % "test"
val scalacheck             = "org.scalacheck"             %%  "scalacheck"            % "1.12.5"
val `reactive-streams-tck` = "org.reactivestreams"        %   "reactive-streams-tck"  % "1.0.0"   % "test"

// examples
val `akka-http-core`       = "com.typesafe.akka"          %%  "akka-http-core"        % "2.4.8"
val logback                = "ch.qos.logback"             %   "logback-classic"       % "1.1.7"

/////////////////////// PROJECTS /////////////////////////

lazy val swave = project.in(file("."))
  .aggregate(akkaCompat, benchmarks, core, `core-macros`, `core-tests`, examples, scodecCompat, testkit)
  .settings(commonSettings: _*)
  .settings(releaseSettings: _*)
  .settings(noPublishingSettings: _*)

lazy val akkaCompat = project
  .dependsOn(core, `core-macros` % "compile-internal", testkit)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(releaseSettings: _*)
  .settings(publishingSettings: _*)
  .settings(
    moduleName := "swave-akka-compat",
    libraryDependencies ++= Seq(`akka-stream`, scalatest))

lazy val benchmarks = project
  .dependsOn(core)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(noPublishingSettings: _*)

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
      scalatest, scalacheck % "test"))

lazy val `core-macros` = project
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(noPublishingSettings: _*)
  .settings(
    macroParadise,
    libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value)

lazy val `core-tests` = project
  .dependsOn(core, testkit, `core-macros` % "test-internal")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(noPublishingSettings: _*)
  .settings(libraryDependencies ++= Seq(shapeless, scalatest, `reactive-streams-tck`, scalacheck % "test", logback % "test"))

lazy val examples = project
  .dependsOn(core, akkaCompat)
  .enablePlugins(AutomateHeaderPlugin)
  .disablePlugins(com.typesafe.sbt.SbtScalariform)
  .settings(commonSettings: _*)
  .settings(noPublishingSettings: _*)
  .settings(
    fork in run := true,
    connectInput in run := true,
    javaOptions in run ++= Seq("-XX:+UnlockCommercialFeatures", "-XX:+FlightRecorder"),
    libraryDependencies ++= Seq(`akka-stream`, `akka-http-core`, logback))

lazy val scodecCompat = project
  .dependsOn(core, `core-macros` % "compile-internal", testkit, `core-tests` % "test->test")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(releaseSettings: _*)
  .settings(publishingSettings: _*)
  .settings(
    moduleName := "swave-scodec-compat",
    libraryDependencies ++= Seq(`scodec-bits`, scalatest, logback % "test"))

lazy val testkit = project
  .dependsOn(core, `core-macros` % "compile-internal")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(releaseSettings: _*)
  .settings(publishingSettings: _*)
  .settings(
    moduleName := "swave-testkit",
    macroParadise,
    libraryDependencies ++= Seq(scalacheck))
