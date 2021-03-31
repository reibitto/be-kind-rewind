import Build.Version
import sbt.Keys._
import sbt._
import sbtwelcome._

lazy val root = project
  .in(file("."))
  .aggregate(
    core,
    clientSttp,
    clientPlay
  )
  .settings(
    name := "be-kind-rewind",
    addCommandAlias("fmt", "all root/scalafmtSbt root/scalafmtAll"),
    addCommandAlias("fmtCheck", "all root/scalafmtSbtCheck root/scalafmtCheckAll"),
    logo :=
      s"""
         |
         | _____        _____ _       _    _____           _       _
         || __  |___   |  |  |_|___ _| |  | __  |___ _ _ _|_|___ _| |
         || __ -| -_|  |    -| |   | . |  |    -| -_| | | | |   | . |
         ||_____|___|  |__|__|_|_|_|___|  |__|__|___|_____|_|_|_|___|
         |
         |                       ${version.value}
         |
         |""".stripMargin,
    usefulTasks := Seq(
      UsefulTask("a", "~compile", "Compile all modules with file-watch enabled"),
      UsefulTask("b", "fmt", "Run scalafmt on the entire project")
    )
  )

lazy val core = module("be-kind-rewind", Some("core"))
  .settings(
    fork := true,
    baseDirectory in run := file("."),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core"   % Version.circe,
      "io.circe" %% "circe-parser" % Version.circe
    )
  )

lazy val clientSttp = module("be-kind-rewind-sttp", Some("client-sttp"))
  .settings(
    fork := true,
    baseDirectory in run := file("."),
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %% "core"  % Version.sttp,
      "com.softwaremill.sttp.client3" %% "circe" % Version.sttp
    )
  )
  .dependsOn(core)

lazy val clientPlayStandalone = module("be-kind-rewind-play-standalone", Some("client-play-standalone"))
  .settings(
    fork := true,
    baseDirectory in run := file("."),
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-ahc-ws-standalone" % "2.1.3"
    )
  )
  .dependsOn(core)

lazy val clientPlay = module("be-kind-rewind-play", Some("client-play"))
  .settings(
    fork := true,
    baseDirectory in run := file("."),
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-ws" % "2.8.7"
    )
  )
  .dependsOn(core)

def module(projectId: String, moduleFile: Option[String] = None): Project =
  Project(id = projectId, base = file(moduleFile.getOrElse(projectId)))
    .settings(Build.defaultSettings(projectId))

ThisBuild / organization := "com.github.reibitto"
ThisBuild / version := Build.BeKindRewindVersion
