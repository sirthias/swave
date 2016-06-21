import scalariform.formatter.preferences._
import de.heikoseeberger.sbtheader.HeaderPattern
import com.typesafe.sbt.SbtScalariform._

val commonSettings = Seq(
  version := "0.5-M1",
  organization := "io.swave",
  scalaVersion := "2.11.8",
  homepage := Some(new URL("http://swave.io")),
  description := "A Reactive Streams infrastructure implementation in Scala",
  startYear := Some(2016),
  licenses := Seq("MPL 2.0" -> new URL("https://www.mozilla.org/en-US/MPL/2.0/")),
  javacOptions ++= commonJavacOptions,
  scalacOptions ++= commonScalacOptions,
  scalacOptions in Test ~= (_ filterNot (_ == "-Ywarn-value-discard")),
  scalacOptions in (Test, console) ~= { _ filterNot { o => o == "-Ywarn-unused-import" || o == "-Xfatal-warnings" } },
  scalacOptions in (Compile, doc) ~= { _.filterNot(o => o == "-Xlint" || o == "-Xfatal-warnings").:+("-nowarn") },
  headers := Map("scala" -> (
    HeaderPattern.cStyleBlockComment,
    """/* This Source Code Form is subject to the terms of the Mozilla Public
      | * License, v. 2.0. If a copy of the MPL was not distributed with this
      | * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
      |
      |""".stripMargin)))

val formattingSettings = Seq(
  ScalariformKeys.preferences := ScalariformKeys.preferences.value
    .setPreference(AlignParameters, false)
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(DanglingCloseParenthesis, Prevent)
    .setPreference(DoubleIndentClassDeclaration, true)
    .setPreference(PreserveSpaceBeforeArguments, true)
    .setPreference(RewriteArrowSymbols, true))

val publishingSettings = Seq(
  publishMavenStyle := true,
  useGpg := true,
  publishTo <<= version { v: String =>
    val nexus = "https://oss.sonatype.org/"
    if (v.trim.endsWith("SNAPSHOT")) Some("snapshots" at nexus + "content/repositories/snapshots")
    else                             Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  pomIncludeRepository := { _ => false },
  pomExtra :=
    <scm>
      <url>git@github.com:sirthias/swave.git</url>
      <connection>scm:git:git@github.com:sirthias/swave.git</connection>
    </scm>
    <developers>
      <developer>
        <id>sirthias</id>
        <name>Mathias Doenitz</name>
        <url>https://github.com/sirthias/</url>
      </developer>
    </developers>)

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

val noPublishingSettings = Seq(
  publish := (),
  publishLocal := (),
  publishArtifact := false)

val macroParadise = Seq(
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full))

/////////////////////// DEPENDENCIES /////////////////////////

// core
val `reactive-streams`     = "org.reactivestreams"        %   "reactive-streams"      % "1.0.0"
val `jctools-core`         = "org.jctools"                %   "jctools-core"          % "1.2"
val `typesafe-config`      = "com.typesafe"               %   "config"                % "1.3.0"
val shapeless              = "com.chuusai"                %%  "shapeless"             % "2.3.1"
val `scala-logging`        = "com.typesafe.scala-logging" %%  "scala-logging"         % "3.4.0"

// test
val scalatest              = "org.scalatest"              %%  "scalatest"             % "2.2.6"   % "test"
val scalacheck             = "org.scalacheck"             %%  "scalacheck"            % "1.12.5"
val `reactive-streams-tck` = "org.reactivestreams"        %   "reactive-streams-tck"  % "1.0.0"   % "test"

// examples
val `akka-stream`          = "com.typesafe.akka"          %%  "akka-stream"           % "2.4.7"
val logback                = "ch.qos.logback"             %   "logback-classic"       % "1.1.7"

/////////////////////// PROJECTS /////////////////////////

lazy val swave = project.in(file("."))
  .aggregate(benchmarks, examples, core, `core-tests`, testkit)
  .settings(commonSettings: _*)
  .settings(noPublishingSettings: _*)

lazy val benchmarks = project
  .dependsOn(core)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(noPublishingSettings: _*)

lazy val examples = project
  .dependsOn(core)
  .enablePlugins(AutomateHeaderPlugin)
  .disablePlugins(com.typesafe.sbt.SbtScalariform)
  .settings(commonSettings: _*)
  .settings(noPublishingSettings: _*)
  .settings(
    fork in run := true,
    connectInput in run := true,
    javaOptions in run ++= Seq("-XX:+UnlockCommercialFeatures", "-XX:+FlightRecorder"),
    libraryDependencies ++= Seq(`akka-stream`, logback))

lazy val core = Project("swave-core", file("core"))
  .dependsOn(`core-macros` % "compile-internal, test-internal")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(macroParadise: _*)
  .settings(formattingSettings: _*)
  .settings(publishingSettings: _*)
  .settings(
    libraryDependencies ++= Seq(`reactive-streams`,  `jctools-core`, `typesafe-config`, shapeless, `scala-logging`,
      scalatest, scalacheck % "test"))

lazy val `core-tests` = project
  .dependsOn(core, testkit, `core-macros` % "test-internal")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(formattingSettings: _*)
  .settings(noPublishingSettings: _*)
  .settings(libraryDependencies ++= Seq(shapeless, scalatest, `reactive-streams-tck`, scalacheck % "test", logback % "test"))

lazy val `core-macros` = project
  .settings(commonSettings: _*)
  .settings(macroParadise: _*)
  .settings(formattingSettings: _*)
  .settings(noPublishingSettings: _*)
  .settings(libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value)

lazy val testkit = Project("swave-testkit", file("testkit"))
  .dependsOn(core, `core-macros` % "compile-internal")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(commonSettings: _*)
  .settings(macroParadise: _*)
  .settings(formattingSettings: _*)
  .settings(publishingSettings: _*)
  .settings(libraryDependencies ++= Seq(scalacheck))
