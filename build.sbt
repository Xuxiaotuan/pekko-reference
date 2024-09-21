val projectName      = "pekko-reference"
val projectVersion   = "1.0"
val scala            = "2.13.12"
val pekkoVersion     = "1.0.3"
val scalatestVersion = "3.2.19"
val logbackVersion   = "1.4.12"

// Run in a separate JVM, to make sure sbt waits until all threads have
// finished before returning.
// If you want to keep the application running while executing other
// sbt tasks, consider https://github.com/spray/sbt-revolver/
fork := true

lazy val `pekko-reference` = project
  .in(file("."))
  .settings(
    organization := "cn.xuyinyin",
    version      := projectVersion,
    version      := projectVersion,
    scalaVersion := scala,
    name         := projectName,
    Compile / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
    Compile / javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),
    run / javaOptions ++= Seq("-Xms128m", "-Xmx1024m", "-Djava.library.path=./target/native"),
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed"           % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-typed"         % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
      "ch.qos.logback"    % "logback-classic"             % logbackVersion,
      "org.scalatest"    %% "scalatest"                   % scalatestVersion % Test,
      "org.apache.pekko" %% "pekko-multi-node-testkit"    % pekkoVersion     % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed"   % pekkoVersion     % Test),
    run / fork          := false,
    Global / cancelable := false,
    // disable parallel tests
    Test / parallelExecution := false,
    licenses                 := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0"))))
