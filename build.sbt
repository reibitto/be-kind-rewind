import Build.Version
import sbt.Keys._
import sbt._
import sbtwelcome._

inThisBuild(
  List(
    organization := "com.github.reibitto",
    homepage     := Some(url("https://github.com/reibitto/be-kind-rewind")),
    licenses     := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
    developers   := List(
      Developer("reibitto", "reibitto", "reibitto@users.noreply.github.com", url("https://reibitto.github.io"))
    )
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(
    core,
    codecCirceJson,
    codecCirceYaml,
    clientAkkaHttp,
    clientSttp,
    clientPlay,
    clientPlayStandalone
  )
  .settings(
    name        := "be-kind-rewind",
    addCommandAlias("fmt", "all root/scalafmtSbt root/scalafmtAll"),
    addCommandAlias("fmtCheck", "all root/scalafmtSbtCheck root/scalafmtCheckAll"),
    logo        :=
      s"""
         |
         | _____        _____ _       _    _____           _       _
         || __  |___   |  |  |_|___ _| |  | __  |___ _ _ _|_|___ _| |
         || __ -| -_|  |    -| |   | . |  |    -| -_| | | | |   | . |
         ||_____|___|  |__|__|_|_|_|___|  |__|__|___|_____|_|_|_|___|
         |
         |  ${version.value}
         |
         |""".stripMargin,
    usefulTasks := Seq(
      UsefulTask("a", "~compile", "Compile all modules with file-watch enabled"),
      UsefulTask("b", "fmt", "Run scalafmt on the entire project")
    )
  )

lazy val core = module("be-kind-rewind", Some("core"))
  .settings(
    fork                := true,
    run / baseDirectory := file("."),
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit"            % Version.munit % Test,
      "org.scalameta" %% "munit-scalacheck" % Version.munit % Test
    ),
    buildInfoKeys       := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage    := "bekindrewind"
  )
  .enablePlugins(BuildInfoPlugin)

lazy val codecCirceJson = module("be-kind-rewind-codec-circe-json", Some("codec-circe-json"))
  .settings(
    fork                := true,
    run / baseDirectory := file("."),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core"   % Version.circe,
      "io.circe" %% "circe-parser" % Version.circe
    )
  )
  .dependsOn(core)

lazy val codecCirceYaml = module("be-kind-rewind-codec-circe-yaml", Some("codec-circe-yaml"))
  .settings(
    fork                := true,
    run / baseDirectory := file("."),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-yaml" % Version.circeYaml
    )
  )
  .dependsOn(core)

lazy val clientSttp = module("be-kind-rewind-sttp", Some("client-sttp"))
  .settings(
    fork                := true,
    run / baseDirectory := file("."),
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %% "core"  % Version.sttp,
      "com.softwaremill.sttp.client3" %% "circe" % Version.sttp,
      "org.scalameta"                 %% "munit" % Version.munit % Test
    )
  )
  .dependsOn(core)

lazy val clientPlayStandalone = module("be-kind-rewind-play-standalone", Some("client-play-standalone"))
  .settings(
    fork                := true,
    run / baseDirectory := file("."),
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-ahc-ws-standalone" % "2.1.3"
    )
  )
  .dependsOn(core)

lazy val clientPlay = module("be-kind-rewind-play", Some("client-play"))
  .settings(
    fork                := true,
    run / baseDirectory := file("."),
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-ws" % "2.8.21"
    )
  )
  .dependsOn(core)

lazy val clientAkkaHttp = module("be-kind-rewind-akka-http", Some("client-akka-http"))
  .settings(
    fork                := true,
    run / baseDirectory := file("."),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http"        % Version.akkaHttp,
      "com.typesafe.akka" %% "akka-stream"      % Version.akka  % Provided,
      "com.typesafe.akka" %% "akka-actor-typed" % Version.akka  % Provided,
      "org.scalameta"     %% "munit"            % Version.munit % Test
    )
  )
  .dependsOn(core)

def module(projectId: String, moduleFile: Option[String] = None): Project =
  Project(id = projectId, base = file(moduleFile.getOrElse(projectId)))
    .settings(Build.defaultSettings(projectId))

ThisBuild / organization := "com.github.reibitto"
