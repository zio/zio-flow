addSbtPlugin("ch.epfl.scala"      % "sbt-bloop"                     % "1.5.6")
addSbtPlugin("com.eed3si9n"       % "sbt-buildinfo"                 % "0.11.0")
addSbtPlugin("com.github.sbt"     % "sbt-ci-release"                % "1.5.11")
addSbtPlugin("com.github.cb372"   % "sbt-explicit-dependencies"     % "0.2.16")
addSbtPlugin("com.typesafe"       % "sbt-mima-plugin"               % "1.1.1")
addSbtPlugin("de.heikoseeberger"  % "sbt-header"                    % "5.9.0")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.2.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"      % "1.2.0")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"                   % "1.13.0")
addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.4.10")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"                  % "2.5.0")
addSbtPlugin("pl.project13.scala" % "sbt-jcstress"                  % "0.2.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh"                       % "0.4.4")
addSbtPlugin("dev.zio"            % "zio-sbt-website"               % "0.3.6")
addSbtPlugin("org.scoverage"      % "sbt-scoverage"                 % "2.0.7")

libraryDependencies += "org.scalameta" % "scalameta_2.12" % "4.7.4"

ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
