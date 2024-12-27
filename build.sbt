val scala = "2.13.12"

val projectName         = "pekko-reference"
val organizationName    = "cn.xuyinyin"
val projectVersion      = "0.1"
val pekkoVersion        = "1.0.3"
val scalatestVersion    = "3.2.19"
val logbackVersion      = "1.2.11"
val scalaLoggingVersion = "3.9.5"

lazy val commonSettings = Seq(
  organization := organizationName,
  version      := projectVersion,
  scalaVersion := scala,
  maintainer   := "jia_yangchen@163.com, aka Xuxiaotuan",
  name         := projectName,
  Compile / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
  Compile / javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation", "-source", "1.8", "-target", "1.8"),
  run / javaOptions ++= Seq("-Xms128m", "-Xmx1024m", "-Djava.library.path=./target/native"),
  libraryDependencies ++= Seq(
    "com.typesafe.scala-logging" %% "scala-logging"   % scalaLoggingVersion,
    "ch.qos.logback"              % "logback-classic" % logbackVersion,
    "org.scalatest"              %% "scalatest"       % scalatestVersion % Test,
  )
)

lazy val root = Project(id = "pekko-reference", base = file("."))
  .settings(commonSettings)
  .aggregate(pekkoServer)

import com.typesafe.sbt.packager.docker.Cmd

lazy val pekkoServer = Project(id = "pekko-server", base = file("pekko-server"))
  .enablePlugins(JavaServerAppPackaging, DockerPlugin, AshScriptPlugin)
  .settings(
    commonSettings ++ Seq(
      Compile / mainClass := Some("cn.xuyinyin.magic.PekkoServer"),
      Universal / javaOptions ++= Seq("-J-Xms256m", "-J-Xmx512m"),
      bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../conf/application.conf"""",
      Docker / packageName               := projectName,
      Docker / version                   := projectVersion,
      Docker / daemonUser                := projectName,
      dockerBaseImage                    := "openjdk:8-jre-alpine",
      dockerExposedPorts                 := Seq(9906),
      dockerUpdateLatest                 := true,
      unmanagedResources / excludeFilter := "application.conf",
      dockerCommands ++= Seq(
        Cmd("USER", "root"),
        Cmd("RUN", "ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime"),
        Cmd("RUN", "echo 'Asia/Shanghai' > /etc/timezone"),
      )
    ),
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed"           % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-typed"         % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream"                % pekkoVersion,

      // -test
      "org.apache.pekko" %% "pekko-stream-testkit"        % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-multi-node-testkit"    % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed"   % pekkoVersion % Test),
  )
