import sbt._
import Keys._
import explicitdeps.ExplicitDepsPlugin.autoImport._
import sbtcrossproject.CrossPlugin.autoImport._
import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbtbuildinfo._
import BuildInfoKeys._

object BuildHelper {
  val Scala211 = "2.11.12"
  val Scala212 = "2.12.15"
  val Scala213 = "2.13.7"
  val Scala3   = "3.1.0"

  val SilencerVersion = "1.7.7"

  private val stdOptions = Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-unchecked"
  ) ++ {
    if (sys.env.contains("CI")) {
      Seq("-Xfatal-warnings")
    } else {
      Nil
    }
  }

  private val std2xOptions = Seq(
    "-language:higherKinds",
    "-language:existentials",
    "-explaintypes",
    "-Yrangepos",
    "-Xlint:_,-missing-interpolator,-type-parameter-shadow",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard"
  )

  private def optimizerOptions(optimize: Boolean) =
    if (optimize)
      Seq(
        "-opt:l:inline",
        "-opt-inline-from:zio.internal.**"
      )
    else Nil

  def buildInfoSettings(packageName: String) =
    Seq(
      buildInfoKeys := Seq[BuildInfoKey](organization, moduleName, name, version, scalaVersion, sbtVersion, isSnapshot),
      buildInfoPackage := packageName
    )

  val dottySettings = Seq(
    crossScalaVersions += Scala3,
    sources in (Compile, doc) := {
      val old = (Compile / doc / sources).value
      if (scalaVersion.value == Scala3) {
        Nil
      } else {
        old
      }
    },
    parallelExecution in Test := {
      val old = (Test / parallelExecution).value
      if (scalaVersion.value == Scala3) {
        false
      } else {
        old
      }
    }
  )

  val scalaReflectSettings = Seq(
    libraryDependencies ++= Seq("dev.zio" %%% "izumi-reflect" % "1.0.0-M10")
  )

  // Keep this consistent with the version in .core-tests/shared/src/test/scala/REPLSpec.scala
  val replSettings = makeReplSettings {
    """|import zio._
       |import zio.console._
       |import zio.duration._
       |import zio.Runtime.default._
       |implicit class RunSyntax[A](io: ZIO[ZEnv, Any, A]){ def unsafeRun: A = Runtime.default.unsafeRun(io.provideLayer(ZEnv.live)) }
    """.stripMargin
  }

  // Keep this consistent with the version in .streams-tests/shared/src/test/scala/StreamREPLSpec.scala
  val streamReplSettings = makeReplSettings {
    """|import zio._
       |import zio.console._
       |import zio.duration._
       |import zio.stream._
       |import zio.Runtime.default._
       |implicit class RunSyntax[A](io: ZIO[ZEnv, Any, A]){ def unsafeRun: A = Runtime.default.unsafeRun(io.provideLayer(ZEnv.live)) }
    """.stripMargin
  }

  def makeReplSettings(initialCommandsStr: String) = Seq(
    // In the repl most warnings are useless or worse.
    // This is intentionally := as it's more direct to enumerate the few
    // options we do want than to try to subtract off the ones we don't.
    // One of -Ydelambdafy:inline or -Yrepl-class-based must be given to
    // avoid deadlocking on parallel operations, see
    //   https://issues.scala-lang.org/browse/SI-9076
    scalacOptions in Compile in console := Seq(
      "-Ypartial-unification",
      "-language:higherKinds",
      "-language:existentials",
      "-Yno-adapted-args",
      "-Xsource:2.13",
      "-Yrepl-class-based"
    ),
    initialCommands in Compile in console := initialCommandsStr
  )

  def extraOptions(scalaVersion: String, optimize: Boolean) =
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((3, _))  =>
        Seq(
          "-language:implicitConversions",
          "-Xignore-scala2-macros"
        )
      case Some((2, 13)) =>
        Seq(
          "-Ywarn-unused:params,-implicits"
        ) ++ std2xOptions ++ optimizerOptions(optimize)
      case Some((2, 12)) =>
        Seq(
          "-opt-warnings",
          "-Ywarn-extra-implicit",
          "-Ywarn-unused:_,imports",
          "-Ywarn-unused:imports",
          "-Ypartial-unification",
          "-Yno-adapted-args",
          "-Ywarn-inaccessible",
          "-Ywarn-infer-any",
          "-Ywarn-nullary-override",
          "-Ywarn-nullary-unit",
          "-Ywarn-unused:params,-implicits",
          "-Xfuture",
          "-Xsource:2.13",
          "-Xmax-classfile-name",
          "242"
        ) ++ std2xOptions ++ optimizerOptions(optimize)
      case Some((2, 11)) =>
        Seq(
          "-Ypartial-unification",
          "-Yno-adapted-args",
          "-Ywarn-inaccessible",
          "-Ywarn-infer-any",
          "-Ywarn-nullary-override",
          "-Ywarn-nullary-unit",
          "-Xexperimental",
          "-Ywarn-unused-import",
          "-Xfuture",
          "-Xsource:2.13",
          "-Xmax-classfile-name",
          "242"
        ) ++ std2xOptions
      case _             => Seq.empty
    }

  def platformSpecificSources(platform: String, conf: String, baseDirectory: File)(versions: String*) = for {
    platform <- List("shared", platform)
    version  <- "scala" :: versions.toList.map("scala-" + _)
    result    = baseDirectory.getParentFile / platform.toLowerCase / "src" / conf / version
    if result.exists
  } yield result

  def crossPlatformSources(scalaVer: String, platform: String, conf: String, baseDir: File) = {
    val versions = CrossVersion.partialVersion(scalaVer) match {
      case Some((2, 11)) =>
        List("2.11", "2.11+", "2.11-2.12", "2.x")
      case Some((2, 12)) =>
        List("2.12", "2.11+", "2.12+", "2.11-2.12", "2.12-2.13", "2.x")
      case Some((2, 13)) =>
        List("2.13", "2.11+", "2.12+", "2.13+", "2.12-2.13", "2.x")
      case Some((3, _))  =>
        List("dotty", "2.11+", "2.12+", "2.13+", "3.x")
      case _             =>
        List()
    }
    platformSpecificSources(platform, conf, baseDir)(versions: _*)
  }

  lazy val crossProjectSettings = Seq(
    Compile / unmanagedSourceDirectories ++= {
      crossPlatformSources(
        scalaVersion.value,
        crossProjectPlatform.value.identifier,
        "main",
        baseDirectory.value
      )
    },
    Test / unmanagedSourceDirectories ++= {
      crossPlatformSources(
        scalaVersion.value,
        crossProjectPlatform.value.identifier,
        "test",
        baseDirectory.value
      )
    }
  )

  def stdSettings(prjName: String) = Seq(
    name := s"$prjName",
    crossScalaVersions := Seq(Scala211, Scala212, Scala213),
    ThisBuild / scalaVersion := Scala213,
    scalacOptions := stdOptions ++ extraOptions(scalaVersion.value, optimize = !isSnapshot.value),
    libraryDependencies ++= {
      if (scalaVersion.value == Scala3)
        Seq(
          "com.github.ghik" % s"silencer-lib_$Scala213" % SilencerVersion % Provided
        )
      else
        Seq(
          "com.github.ghik" % "silencer-lib"            % SilencerVersion % Provided cross CrossVersion.full,
          compilerPlugin("com.github.ghik" % "silencer-plugin" % SilencerVersion cross CrossVersion.full)
        )
    },
    parallelExecution in Test := true,
    incOptions ~= (_.withLogRecompileOnMacro(false)),
    autoAPIMappings := true,
    unusedCompileDependenciesFilter -= moduleFilter("org.scala-js", "scalajs-library")
  )

  def macroExpansionSettings = Seq(
    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 13)) => Seq("-Ymacro-annotations")
        case _             => Seq.empty
      }
    },
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, x)) if x <= 12 =>
          Seq(compilerPlugin(("org.scalamacros" % "paradise" % "2.1.1").cross(CrossVersion.full)))
        case _                       => Seq.empty
      }
    }
  )

  def macroDefinitionSettings = Seq(
    scalacOptions += "-language:experimental.macros",
    libraryDependencies ++= {
      if (scalaVersion.value == Scala3) Seq()
      else
        Seq(
          "org.scala-lang" % "scala-reflect"  % scalaVersion.value % "provided",
          "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided"
        )
    }
  )

  def jsSettings = Seq(
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time"      % "2.1.0",
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time-tzdb" % "2.1.0"
  )

  def nativeSettings = Seq(
    Test / skip := true,
    doc / skip := true,
    SettingKey[Boolean](
      "ide-skip-project" // Exclude from Intellij because Scala Native projects break it - https://github.com/scala-native/scala-native/issues/1007#issuecomment-370402092
    ) := true,
    Compile / doc / sources := Seq.empty
  )

  def welcomeMessage = onLoadMessage := {
    import scala.Console

    def header(text: String): String = s"${Console.RED}$text${Console.RESET}"

    def item(text: String): String    = s"${Console.GREEN}> ${Console.CYAN}$text${Console.RESET}"
    def subItem(text: String): String = s"  ${Console.YELLOW}> ${Console.CYAN}$text${Console.RESET}"

    s"""|${header(" ________ ___")}
        |${header("|__  /_ _/ _ \\")}
        |${header("  / / | | | | |")}
        |${header(" / /_ | | |_| |")}
        |${header(s"/____|___\\___/   ${version.value}")}
        |
        |Useful sbt tasks:
        |${item("build")} - Prepares sources, compiles and runs tests.
        |${item("fmt")} - Formats source files using scalafmt
        |${item("~compileJVM")} - Compiles all JVM modules (file-watch enabled)
        |${item("testJVM")} - Runs all JVM tests
        |${item("testJS")} - Runs all ScalaJS tests
        |${item("testOnly *.YourSpec -- -t \"YourLabel\"")} - Only runs tests with matching term e.g.
        |${subItem("coreTestsJVM/testOnly *.ZIOSpec -- -t \"happy-path\"")}
        |${item("docs/docusaurusCreateSite")} - Generates the ZIO microsite
      """.stripMargin
  }

  implicit class ModuleHelper(p: Project) {
    def module: Project = p.in(file(p.id)).settings(stdSettings(p.id))
  }
}
