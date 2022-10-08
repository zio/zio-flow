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
    resolvers +=
      "Sonatype OSS Snapshots 01" at "https://s01.oss.sonatype.org/content/repositories/snapshots",
    pgpPassphrase := sys.env.get("PGP_PASSWORD").map(_.toArray),
    pgpPublicRing := file("/tmp/public.asc"),
    pgpSecretRing := file("/tmp/secret.asc"),
    resolvers +=
      "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
  )
)

addCommandAlias("fmt", "all scalafmtSbt scalafmtAll")
addCommandAlias("fmtCheck", "all scalafmtSbtCheck scalafmtCheckAll")

lazy val commonTestDependencies =
  Seq(
    "dev.zio" %% "zio-test"     % Version.zio,
    "dev.zio" %% "zio-test-sbt" % Version.zio
  )

lazy val zioTest = new TestFramework("zio.test.sbt.ZTestFramework")

lazy val root = project
  .in(file("."))
  .settings(
    publish / skip := true
  )
  .aggregate(
    rocksdb,
    cassandra,
    dynamodb,
    docs,
    examplesJVM,
    examplesJS,
    zioFlowJVM,
    zioFlowJS,
    testJVM,
    testJS,
    // activity libraries
    twilioJVM,
    twilioJS
  )

lazy val zioFlow = crossProject(JSPlatform, JVMPlatform)
  .in(file("zio-flow"))
  .settings(stdSettings("zio-flow"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.flow"))
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"                   % Version.zio,
      "dev.zio" %% "zio-schema"            % Version.zioSchema,
      "dev.zio" %% "zio-schema-derivation" % Version.zioSchema,
      "dev.zio" %% "zio-schema-optics"     % Version.zioSchema,
      "dev.zio" %% "zio-schema-json"       % Version.zioSchema,
      "dev.zio" %% "zio-schema-protobuf"   % Version.zioSchema,
      "io.d11"  %% "zhttp"                 % Version.zioHttp
    ) ++
      commonTestDependencies.map(_ % Test)
  )
  .settings(fork := false)
  .settings(testFrameworks += zioTest)
  .enablePlugins(RemoteTupleGenerator)

lazy val zioFlowJS = zioFlow.js
  .settings(scalaJSUseMainModuleInitializer := true)
lazy val zioFlowJVM = zioFlow.jvm

lazy val zioFlowServer = project
  .in(file("zio-flow-server"))
  .dependsOn(zioFlowJVM)
  .settings(stdSettings("zio-flow-server"))
  .settings(
    libraryDependencies ++= commonTestDependencies.map(_ % Test)
  )
  .settings(fork := true)
  .settings(testFrameworks += zioTest)

lazy val test = crossProject(JSPlatform, JVMPlatform)
  .in(file("test"))
  .settings(stdSettings("zio-flow-test"))
  .settings(crossProjectSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test" % Version.zio
    )
  )
  .dependsOn(zioFlow)

lazy val testJS  = test.js
lazy val testJVM = test.jvm

// Database implementations

lazy val rocksdb = project
  .in(file("rocksdb"))
  .dependsOn(
    zioFlowJVM,
    testJVM % "it->compile"
  )
  .configs(IntegrationTest)
  .settings(
    stdSettings("zio-flow-rocksdb"),
    Defaults.itSettings,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-rocksdb" % Version.zioRocksDb
    ) ++ (
      commonTestDependencies ++
        Seq(
          "org.rocksdb" % "rocksdbjni" % Version.rocksDbJni,
          "dev.zio"    %% "zio-nio"    % Version.zioNio
        ),
    ).map(_ % IntegrationTest),
    testFrameworks += zioTest
  )

lazy val cassandra = project
  .in(file("cassandra"))
  .dependsOn(
    zioFlowJVM,
    testJVM % "it->compile"
  )
  .configs(IntegrationTest)
  .settings(
    stdSettings("zio-flow-cassandra"),
    Defaults.itSettings,
    libraryDependencies ++= Seq(
      "com.scylladb"  % "java-driver-core-shaded"   % Version.cassandraJavaDriver,
      ("com.scylladb" % "java-driver-query-builder" % Version.cassandraJavaDriver)
        .exclude("com.scylladb", "java-driver-core")
    ) ++ (
      commonTestDependencies ++
        Seq(
          "com.dimafeng" %% "testcontainers-scala-cassandra" % Version.testContainers,
          "dev.zio"      %% "zio-logging-slf4j-bridge"       % Version.zioLogging
        )
    ).map(_ % IntegrationTest),
    testFrameworks += zioTest
  )

lazy val dynamodb = project
  .in(file("dynamodb"))
  .dependsOn(
    zioFlowJVM,
    testJVM % "it->compile"
  )
  .configs(IntegrationTest)
  .settings(
    stdSettings("zio-flow-dynamodb"),
    Defaults.itSettings,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-aws-dynamodb" % Version.zioAws,
      "dev.zio" %% "zio-aws-netty"    % Version.zioAws
    ) ++ (
      commonTestDependencies ++
        Seq(
          "com.amazonaws" % "aws-java-sdk-core"                  % Version.awsSdkV1,
          "com.dimafeng" %% "testcontainers-scala-localstack-v2" % Version.testContainers,
          "dev.zio"      %% "zio-logging-slf4j-bridge"           % Version.zioLogging
        )
    ).map(_ % IntegrationTest),
    testFrameworks += zioTest
  )

// Activity libraries
lazy val twilio = crossProject(JSPlatform, JVMPlatform)
  .in(file("activities/zio-flow-twilio"))
  .dependsOn(zioFlow, test % "test->compile")
  .settings(
    stdSettings("zio-flow-twilio"),
    testFrameworks += zioTest,
    libraryDependencies ++= Seq(
      "dev.zio"                      %% "zio-schema-derivation" % Version.zioSchema,
      "org.scala-lang"                % "scala-reflect"         % scalaVersion.value % "provided"
    ) ++ commonTestDependencies.map(_ % Test)
  )

lazy val twilioJS  = twilio.js
lazy val twilioJVM = twilio.jvm

// Docs

lazy val docs = project
  .in(file("zio-flow-docs"))
  .settings(
    publish / skip := true,
    moduleName     := "zio-flow-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    scalacOptions += "-Xlog-implicits",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % Version.zio
    ),
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(zioFlowJVM),
    ScalaUnidoc / unidoc / target              := (LocalRootProject / baseDirectory).value / "website" / "static" / "api",
    cleanFiles += (ScalaUnidoc / unidoc / target).value,
    docusaurusCreateSite     := docusaurusCreateSite.dependsOn(Compile / unidoc).value,
    docusaurusPublishGhpages := docusaurusPublishGhpages.dependsOn(Compile / unidoc).value
  )
  .dependsOn(zioFlowJVM)
  .enablePlugins(MdocPlugin, DocusaurusPlugin, ScalaUnidocPlugin)

lazy val examples = crossProject(JSPlatform, JVMPlatform)
  .in(file("zio-flow-examples"))
  .settings(stdSettings("zio-flow-examples"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.flow"))
  .settings((publish / skip) := true)
  .dependsOn(zioFlow)

lazy val examplesJS = examples.js

lazy val examplesJVM = examples.jvm
