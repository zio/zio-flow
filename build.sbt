import BuildHelper._

inThisBuild(
  List(
    organization := "dev.zio",
    homepage     := Some(url("https://zio.github.io/zio-flow/")),
    licenses     := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "jdegoes",
        "John De Goes",
        "john@degoes.net",
        url("http://degoes.net")
      )
    ),
    pgpPassphrase := sys.env.get("PGP_PASSWORD").map(_.toArray),
    pgpPublicRing := file("/tmp/public.asc"),
    pgpSecretRing := file("/tmp/secret.asc")
  )
)

addCommandAlias("fmt", "all scalafmtSbt scalafmtAll")
addCommandAlias("fmtCheck", "all scalafmtSbtCheck scalafmtCheckAll")

val zioVersion        = "1.0.12"
val zioRocksDbVersion = "0.3.0"
val zioSchemaVersion  = "0.1.4"

lazy val root = project
  .in(file("."))
  .settings(
    skip in publish := true,
    unusedCompileDependenciesFilter -= moduleFilter("org.scala-js", "scalajs-library")
  )
  .aggregate(
    docs,
    examplesJVM,
    examplesJS,
    zioFlowJVM,
    zioFlowJS
  )

lazy val zioFlow = crossProject(JSPlatform, JVMPlatform)
  .in(file("zio-flow"))
  .settings(stdSettings("zio-flow"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.flow"))
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"                   % zioVersion,
      "dev.zio" %% "zio-rocksdb"           % zioRocksDbVersion,
      "dev.zio" %% "zio-test"              % zioVersion % "test",
      "dev.zio" %% "zio-test-sbt"          % zioVersion % "test",
      "dev.zio" %% "zio-schema"            % zioSchemaVersion,
      "dev.zio" %% "zio-schema-derivation" % zioSchemaVersion
    )
  )
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))

lazy val zioFlowJS = zioFlow.js
  .settings(scalaJSUseMainModuleInitializer := true)

lazy val zioFlowJVM = zioFlow.jvm
  .settings(dottySettings)

lazy val docs = project
  .in(file("zio-flow-docs"))
  .settings(
    skip.in(publish) := true,
    moduleName       := "zio-flow-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    scalacOptions += "-Xlog-implicits",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion
    ),
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(zioFlowJVM),
    target in (ScalaUnidoc, unidoc)              := (baseDirectory in LocalRootProject).value / "website" / "static" / "api",
    cleanFiles += (target in (ScalaUnidoc, unidoc)).value,
    docusaurusCreateSite     := docusaurusCreateSite.dependsOn(unidoc in Compile).value,
    docusaurusPublishGhpages := docusaurusPublishGhpages.dependsOn(unidoc in Compile).value
  )
  .dependsOn(zioFlowJVM)
  .enablePlugins(MdocPlugin, DocusaurusPlugin, ScalaUnidocPlugin)

lazy val examples = crossProject(JSPlatform, JVMPlatform)
  .in(file("zio-flow-examples"))
  .settings(stdSettings("zio-flow-examples"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.flow"))
  .settings(skip.in(publish) := true)
  .dependsOn(zioFlow)

lazy val examplesJS = examples.js
  .settings(dottySettings)

lazy val examplesJVM = examples.jvm
  .settings(dottySettings)
