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
    ),
    scalaVersion := Build.ScalaVersion
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(
    core,
    codecCirceJson,
    codecCirceYaml,
    clientPekkoHttp,
    clientSttp,
    clientSttp4,
    clientJavaHttp,
    clientPlay,
    clientPlayStandalone,
    testMunit,
    testScalaTest,
    testZio
  )
  .settings(
    name           := "be-kind-rewind",
    publish / skip := true,
    addCommandAlias("fmt", "all root/scalafmtSbt root/scalafmtAll"),
    addCommandAlias("fmtCheck", "all root/scalafmtSbtCheck root/scalafmtCheckAll"),
    logo           :=
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
    usefulTasks    := Seq(
      UsefulTask("~compile", "Compile all modules with file-watch enabled"),
      UsefulTask("fmt", "Run scalafmt on the entire project")
    )
  )

lazy val core = module("be-kind-rewind", Some("core"))
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit"            % Version.munit           % Test,
      "org.scalameta" %% "munit-scalacheck" % Version.munitScalaCheck % Test
    ),
    buildInfoKeys    := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "bekindrewind"
  )
  .enablePlugins(BuildInfoPlugin)

lazy val codecCirceJson = module("be-kind-rewind-codec-circe-json", Some("codec-circe-json"))
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core"   % Version.circe,
      "io.circe" %% "circe-parser" % Version.circe
    )
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val codecCirceYaml = module("be-kind-rewind-codec-circe-yaml", Some("codec-circe-yaml"))
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-yaml" % Version.circeYaml
    )
  )
  .dependsOn(core % "compile->compile;test->test", codecCirceJson)

lazy val clientSttp = module("be-kind-rewind-sttp", Some("client-sttp"))
  .settings(
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %% "core" % Version.sttp
    )
  )
  .dependsOn(core % "compile->compile;test->test", codecCirceJson % Test)

lazy val clientSttp4 = module("be-kind-rewind-sttp4", Some("client-sttp4"))
  .settings(
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "core" % Version.sttp4
    )
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val clientJavaHttp = module("be-kind-rewind-java-http", Some("client-java-http"))
  .dependsOn(core % "compile->compile;test->test")

lazy val clientPlayStandalone = module("be-kind-rewind-play-standalone", Some("client-play-standalone"))
  .settings(
    libraryDependencies ++= Seq(
      "org.playframework" %% "play-ahc-ws-standalone" % Version.playWsStandalone
    )
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val clientPlay = module("be-kind-rewind-play", Some("client-play"))
  .settings(
    libraryDependencies ++= Seq(
      "org.playframework" %% "play-ws"     % Version.playWs,
      "org.playframework" %% "play-ahc-ws" % Version.playWs % Test
    )
  )
  .dependsOn(core % "compile->compile;test->test", clientPlayStandalone)

lazy val clientPekkoHttp = module("be-kind-rewind-pekko-http", Some("client-pekko-http"))
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-http"        % Version.pekkoHttp,
      "org.apache.pekko" %% "pekko-stream"      % Version.pekko % Provided,
      "org.apache.pekko" %% "pekko-actor-typed" % Version.pekko % Provided
    )
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val testMunit = module("be-kind-rewind-munit", Some("test-munit"))
  .settings(
    libraryDependencies += "org.scalameta" %% "munit" % Version.munit
  )
  .dependsOn(core % "compile->compile;test->test", codecCirceJson, clientJavaHttp % Test)

lazy val testScalaTest = module("be-kind-rewind-scalatest", Some("test-scalatest"))
  .settings(
    libraryDependencies += "org.scalatest" %% "scalatest" % Version.scalaTest,
    testFrameworks += new TestFramework("org.scalatest.tools.Framework")
  )
  .dependsOn(core % "compile->compile;test->test", codecCirceJson, clientJavaHttp % Test)

lazy val testZio = module("be-kind-rewind-zio-test", Some("test-zio"))
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test"     % Version.zio,
      "dev.zio" %% "zio-test-sbt" % Version.zio % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
  .dependsOn(core % "compile->compile;test->test", codecCirceJson, clientJavaHttp % Test)

def module(projectId: String, moduleFile: Option[String] = None): Project =
  Project(id = projectId, base = file(moduleFile.getOrElse(projectId)))
    .settings(Build.defaultSettings(projectId))
