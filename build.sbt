import BuildHelper._

inThisBuild(
  List(
    organization := "dev.zio",
    homepage     := Some(url("https://zio.dev/zio-flow/")),
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
    docs,
    examples,
    zioFlowJVM,
    zioFlowJS,
    zioFlowTestJVM,
    zioFlowTestJS,
    zioFlowRuntime,
    zioFlowRuntimeTest,
    zioFlowServer,
    // database backends
    rocksdb,
    cassandra,
    dynamodb,
    // activity libraries
    twilioJVM,
    twilioJS,
    sendgridJVM,
    sendgridJS
  )

lazy val zioFlow = crossProject(JSPlatform, JVMPlatform)
  .in(file("zio-flow"))
  .settings(stdSettings("zio-flow"))
  .settings(dottySettings)
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
      "io.d11"  %% "zhttp"                 % Version.zioHttp // TODO: remove
    ) ++
      commonTestDependencies.map(_ % Test)
  )
  .settings(fork := false)
  .settings(testFrameworks += zioTest)
  .enablePlugins(RemoteTupleGenerator)

lazy val zioFlowJS = zioFlow.js
  .settings(scalaJSUseMainModuleInitializer := true)
lazy val zioFlowJVM = zioFlow.jvm

lazy val zioFlowRuntime = project
  .in(file("zio-flow-runtime"))
  .dependsOn(
    zioFlowJVM % "compile->compile;test->test",
    zioFlowTestJVM
  )
  .settings(stdSettings("zio-flow-runtime"))
  .settings(dottySettings)
  .settings(
    libraryDependencies ++= Seq(
      "io.d11" %% "zhttp" % Version.zioHttp
    ) ++ commonTestDependencies.map(_ % Test)
  )
  .settings(fork := false)
  .settings(testFrameworks += zioTest)

lazy val zioFlowServer = project
  .in(file("zio-flow-server"))
  .dependsOn(
    zioFlowRuntime % " compile->compile;test->test",
    rocksdb,
    dynamodb,
    cassandra
  )
  .settings(dottySettings)
  .settings(stdSettings("zio-flow-server"))
  .settings(
    libraryDependencies ++= Seq(
      "io.d11"      %% "zhttp"                    % Version.zioHttp,
      "dev.zio"     %% "zio-metrics-connectors"   % Version.zioMetricsConnectors,
      "com.typesafe" % "config"                   % Version.config,
      "dev.zio"     %% "zio-config"               % Version.zioConfig,
      "dev.zio"     %% "zio-config-typesafe"      % Version.zioConfig,
      "dev.zio"     %% "zio-logging"              % Version.zioLogging,
      "dev.zio"     %% "zio-logging-slf4j-bridge" % Version.zioLogging
    ) ++ commonTestDependencies.map(_ % Test),
    fork := true
  )
  .settings(fork := false)
  .settings(testFrameworks += zioTest)

lazy val zioFlowTest = crossProject(JSPlatform, JVMPlatform)
  .in(file("zio-flow-test"))
  .settings(stdSettings("zio-flow-test"))
  .settings(crossProjectSettings)
  .settings(dottySettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test" % Version.zio
    )
  )
  .dependsOn(zioFlow)

lazy val zioFlowTestJS  = zioFlowTest.js
lazy val zioFlowTestJVM = zioFlowTest.jvm

lazy val zioFlowRuntimeTest = project
  .in(file("zio-flow-runtime-test"))
  .settings(stdSettings("zio-flow-runtime-test"))
  .settings(dottySettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test" % Version.zio
    ) ++ commonTestDependencies.map(_ % Test)
  )
  .settings(testFrameworks += zioTest)
  .dependsOn(zioFlowRuntime)

// Database backends

lazy val rocksdb = project
  .in(file("backends/zio-flow-rocksdb"))
  .dependsOn(
    zioFlowJVM,
    zioFlowRuntime,
    zioFlowRuntimeTest % "it->compile"
  )
  .configs(IntegrationTest)
  .settings(
    stdSettings("zio-flow-rocksdb"),
    dottySettings,
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
  .in(file("backends/zio-flow-cassandra"))
  .dependsOn(
    zioFlowJVM,
    zioFlowRuntime,
    zioFlowRuntimeTest % "it->compile"
  )
  .configs(IntegrationTest)
  .settings(
    stdSettings("zio-flow-cassandra"),
    dottySettings,
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
  .in(file("backends/zio-flow-dynamodb"))
  .dependsOn(
    zioFlowJVM,
    zioFlowRuntime,
    zioFlowRuntimeTest % "it->compile"
  )
  .configs(IntegrationTest)
  .settings(
    stdSettings("zio-flow-dynamodb"),
    dottySettings,
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
  .dependsOn(zioFlow, zioFlowTest % "test->compile")
  .settings(
    stdSettings("zio-flow-twilio"),
    dottySettings,
    testFrameworks += zioTest,
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio-schema-derivation" % Version.zioSchema,
      "org.scala-lang" % "scala-reflect"         % scalaVersion.value % "provided"
    ) ++ commonTestDependencies.map(_ % Test)
  )

lazy val twilioJS  = twilio.js
lazy val twilioJVM = twilio.jvm

lazy val sendgrid = crossProject(JSPlatform, JVMPlatform)
  .in(file("activities/zio-flow-sendgrid"))
  .dependsOn(zioFlow, zioFlowTest % "test->compile")
  .settings(
    stdSettings("zio-flow-sendgrid"),
    dottySettings,
    testFrameworks += zioTest,
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio-schema-derivation" % Version.zioSchema,
      "org.scala-lang" % "scala-reflect"         % scalaVersion.value % "provided"
    ) ++ commonTestDependencies.map(_ % Test)
  )

lazy val sendgridJS  = sendgrid.js
lazy val sendgridJVM = sendgrid.jvm

// Docs

lazy val docs = project
  .in(file("zio-flow-docs"))
  .settings(
    publish / skip := true,
    moduleName     := "zio-flow-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    scalacOptions += "-Xlog-implicits",
    libraryDependencies ++= Seq("dev.zio" %% "zio" % Version.zio)
  )
  .dependsOn(zioFlowJVM, twilioJVM, sendgridJVM, zioFlowRuntime, zioFlowTestJVM)
  .enablePlugins(WebsitePlugin)

lazy val examples = project
  .in(file("zio-flow-examples"))
  .settings(stdSettings("zio-flow-examples"))
  .settings(buildInfoSettings("zio.flow"))
  .settings((publish / skip) := true)
  .dependsOn(zioFlowJVM, zioFlowRuntime, twilioJVM, sendgridJVM)
