import sbt.Keys._
import sbt._

object Build {
  val ScalaVersion = "2.13.18"

  object Version {
    val circe            = "0.14.15"
    val circeYaml        = "0.16.1"
    val sttp             = "3.11.0"
    val sttp4            = "4.0.26"
    val scalaTest        = "3.2.20"
    val zio              = "2.1.26"
    val munit            = "1.3.6"
    val munitScalaCheck  = "1.3.1"
    val pekko            = "1.7.0"
    val pekkoHttp        = "1.4.0"
    val playWs           = "3.0.11"
    val playWsStandalone = "3.0.14"
  }

  lazy val ScalacOptions = Def.setting(
    Seq(
      "-encoding",
      "UTF-8",
      "-release",
      "11",
      "-unchecked",
      "-deprecation",
      "-feature",
      "-language:implicitConversions",
      "-language:higherKinds",
      "-Werror",
      "-Xlint:nullary-unit",           // Warn when nullary methods return Unit.
      "-Xlint:inaccessible",           // Warn about inaccessible types in method signatures.
      "-Xlint:missing-interpolator",   // A string literal appears to be missing an interpolator id.
      "-Xlint:doc-detached",           // A Scaladoc comment appears to be detached from its element.
      "-Xlint:private-shadow",         // A private field (or class parameter) shadows a superclass field.
      "-Xlint:type-parameter-shadow",  // A local type parameter shadows a type already in scope.
      "-Xlint:delayedinit-select",     // Selecting member of DelayedInit.
      "-Xlint:stars-align",            // Pattern sequence wildcard must align with sequence component.
      "-Xlint:option-implicit",        // Option.apply used implicit view.
      "-Xlint:poly-implicit-overload", // Parameterized overloaded implicit methods are not visible as view bounds.
      "-Xlint:adapted-args",           // An argument list was modified to match the receiver.
      "-Wextra-implicit"               // Warn when more than one implicit parameter section is defined.
    ) ++
      Seq(
        "-Wunused:imports",  // Warn if an import selector is not referenced.
        "-Wunused:locals",   // Warn if a local definition is unused.
        "-Wunused:privates", // Warn if a private member is unused.
        "-Wunused:implicits" // Warn if an implicit parameter is unused.
      ).filter(_ => shouldWarnForUnusedCode)
  )

  def defaultSettings(projectName: String) =
    Seq(
      name               := projectName,
      Test / javaOptions += "-Duser.timezone=UTC",
      scalacOptions      := ScalacOptions.value,
      libraryDependencies += compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
      autoAPIMappings    := true,
      testFrameworks     := Seq(new TestFramework("munit.Framework")),
      Test / fork        := true,
      Test / logBuffered := false
    )

  def compilerFlag(key: String, default: Boolean): Boolean = {
    val flag = sys.props.get(key).orElse {
      val envVarName = key.replace('.', '_').toUpperCase
      sys.env.get(envVarName)
    }

    val flagValue = flag.map(_.toBoolean).getOrElse(default)

    println(s"${scala.Console.MAGENTA}$key:${scala.Console.RESET} $flagValue")

    flagValue
  }

  lazy val shouldWarnForUnusedCode: Boolean = compilerFlag("scalac.unused.enabled", default = false)

}
