import sbt.Keys._
import sbt._

object Build {
  val Scala213Version = "2.13.6"
  val Scala212Version = "2.12.15"

  val BeKindRewindVersion = "0.1.0"

  object Version {
    val circe = "0.13.0"
    val sttp  = "3.3.15"
    val munit = "0.7.29"

    val akka     = "2.6.16"
    val akkaHttp = "10.2.6"
  }

  lazy val ScalacOptions = Def.setting(
    Seq(
      "-encoding",
      "UTF-8",
      "-unchecked",
      "-deprecation",
      "-feature",
      "-language:postfixOps",
      "-language:implicitConversions",
      "-language:higherKinds",
      "-Xfatal-warnings",
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
      "-Ywarn-extra-implicit"          // Warn when more than one implicit parameter section is defined.
    ) ++
      Seq(
        "-Ywarn-unused:imports",       // Warn if an import selector is not referenced.
        "-Ywarn-unused:locals",        // Warn if a local definition is unused.
        "-Ywarn-unused:privates",      // Warn if a private member is unused.
        "-Ywarn-unused:implicits"      // Warn if an implicit parameter is unused.
      ).filter(_ => shouldWarnForUnusedCode)
  )

  def defaultSettings(projectName: String) =
    Seq(
      name := projectName,
      Test / javaOptions += "-Duser.timezone=UTC",
      scalacOptions := ScalacOptions.value,
      ThisBuild / scalaVersion := Scala213Version,
      ThisBuild / crossScalaVersions := Seq(Scala213Version, Scala212Version),
      libraryDependencies ++= Plugins.BaseCompilerPlugins,
      incOptions ~= (_.withLogRecompileOnMacro(false)),
      autoAPIMappings := true,
      resolvers := Resolvers,
      testFrameworks := Seq(new TestFramework("munit.Framework")),
      Test / fork := true,
      Test / logBuffered := false
    )

  lazy val Resolvers = Seq(
    // Order of resolvers affects resolution time. More general purpose repositories should come first.
    Resolver.sonatypeRepo("releases"),
    Resolver.typesafeRepo("releases"),
    Resolver.jcenterRepo,
    Resolver.sonatypeRepo("snapshots")
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
