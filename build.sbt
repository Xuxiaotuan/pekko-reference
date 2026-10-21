/* Pekko CDC/workflow service: Typed, Sharding, JDBC persistence, Streams and HTTP.
 * Production main: cn.xuyinyin.magic.PekkoServer; Scala 2.13 / Java 17.
 * Historical Actor/CDC/CRDT examples live in Test and are excluded from release JARs.
 * Commands: runServer, testAll, dockerBuild, checkDeps.
 */

// ================================
// 版本定义 - 统一管理所有依赖版本
// ================================
val scalaVersion213      = "2.13.12"
val projectVersion       = "0.1"
val projectName          = "pekko-reference"
val projectOrg           = "cn.xuyinyin"

// Pekko 生态系统
val pekkoVersion         = "1.1.3"
val pekkoHttpVersion     = "1.1.0"
val pekkoManagementVersion = "1.1.1"
val pekkoConnectorsVer   = "1.1.0"

// 数据处理和存储
val calciteVersion       = "1.39.0"      // SQL parser
val protobufVersion     = "4.33.4"      // Avatica 3.x and Connector/J 4.x generated-code compatibility
val h2Version            = "2.3.232"     // Test database
val pekkoPersistenceJdbcVersion = "1.1.1"
val debeziumVersion      = "3.6.1.Final"

// JSON和序列化
val jacksonVersion       = "2.17.2"      // 统一Jackson版本，避免冲突
val sprayJsonVersion     = "1.3.6"

// 日志系统
val logbackVersion       = "1.4.12"
val scalaLoggingVersion  = "3.9.5"

// 监控和指标
val prometheusVersion    = "0.16.0"

// 测试框架
val scalatestVersion     = "3.2.19"      // 统一ScalaTest版本
val scalacheckVersion    = "1.17.0"
val scalatestPlusVersion = "3.2.17.0"

// ================================
// 全局构建设置
// ================================
ThisBuild / scalaVersion := scalaVersion213
ThisBuild / version := projectVersion
ThisBuild / organization := projectOrg

// 并行构建优化
ThisBuild / parallelExecution := true
ThisBuild / fork := true

// ================================
// 通用设置 - 所有模块共享的配置
// ================================
lazy val commonSettings = Seq(
  name := projectName,
  maintainer := "jia_yangchen@163.com, aka Xuxiaotuan",
  
  // ================================
  // Scala编译器选项 - 启用严格检查
  // ================================
  Compile / scalacOptions ++= Seq(
    "-deprecation",              // 显示弃用警告
    "-feature",                  // 显示特性警告
    "-unchecked",                // 显示未检查警告
    "-Xlog-reflective-calls",    // 记录反射调用
    "-Xlint"                     // 启用所有lint检查
  ),
  Compile / scalacOptions += "-release:17",
  
  // ================================
  // Java编译器选项
  // ================================
  Compile / javacOptions ++= Seq(
    "-Xlint:unchecked",
    "-Xlint:deprecation",
    "-source", "17",
    "-target", "17"
  ),
  
  // ================================
  // 运行时JVM配置
  // ================================
  run / javaOptions ++= Seq(
    // 内存配置
    "-Xms128m",
    "-Xmx1024m",
    
    // 本地库路径
    "-Djava.library.path=./target/native"
    
    // Pekko集群种子节点（已在application.conf中配置）
    // "-Dpekko.cluster.seed-nodes.0=pekko://pekko-cluster-system@127.0.0.1:2551"
    
    // GC日志配置 - 临时禁用（可选）
    // "-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags",
    // "-Xlog:gc+heap=trace:file=logs/gc-heap.log:time,uptime",
    // "-Xlog:gc+ref=debug:file=logs/gc-ref.log:time,uptime",
    // "-XX:+UseGCLogFileRotation",
    // "-XX:NumberOfGCLogFiles=5",
    // "-XX:GCLogFileSize=10M"
  ),
  
  Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-l", "cn.xuyinyin.magic.tags.ExternalIntegration"),
  
  // ================================
  // 通用依赖库
  // ================================
  libraryDependencies ++= Seq(
    // 日志系统
    "com.typesafe.scala-logging" %% "scala-logging"   % scalaLoggingVersion,
    "ch.qos.logback"              % "logback-classic" % logbackVersion,
    
    // 测试框架
    "org.scalatest" %% "scalatest" % scalatestVersion % Test
  )
)

// ================================
// Pekko Server 模块
// ================================
import com.typesafe.sbt.packager.docker.Cmd

lazy val pekkoServer = Project(id = "pekko-server", base = file("server"))
  .enablePlugins(JavaServerAppPackaging, DockerPlugin, AshScriptPlugin)
  .settings(
    commonSettings ++ Seq(
      Test / fork := false,
      Test / parallelExecution := false,
      
      // ================================
      // Jackson版本管理 - 解决Scala模块兼容性问题
      // ================================
      dependencyOverrides ++= Seq(
        "com.google.protobuf" % "protobuf-java" % protobufVersion,
        "com.fasterxml.jackson.core"     % "jackson-databind"              % jacksonVersion,
        "com.fasterxml.jackson.core"     % "jackson-core"                  % jacksonVersion,
        "com.fasterxml.jackson.core"     % "jackson-annotations"           % jacksonVersion,
        "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310"       % jacksonVersion,
        "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8"         % jacksonVersion,
        "com.fasterxml.jackson.module"  %% "jackson-module-parameter-names" % jacksonVersion
      ),
      
      // ================================
      // 应用程序配置
      // ================================
      Compile / mainClass := Some("cn.xuyinyin.magic.PekkoServer"),
      
      // ================================
      // Universal JVM memory defaults
      // ================================
      Universal / javaOptions ++= Seq(
        "-J-Xms256m",
        "-J-Xmx512m"
      ),
      
      // ================================
      // Docker配置
      // ================================
      Docker / packageName    := projectName,
      Docker / version        := projectVersion,
      Docker / daemonUser     := projectName,
      dockerBaseImage         := "eclipse-temurin:17-jre-alpine",
      dockerExposedPorts      := Seq(8080),           // HTTP API端口
      dockerUpdateLatest      := true,
      
      // Docker镜像自定义命令
      dockerCommands ++= Seq(
        Cmd("USER", "root"),
        Cmd("RUN", "ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime"),
        Cmd("RUN", "echo 'Asia/Shanghai' > /etc/timezone"),
        Cmd("USER", projectName)
      )
    ),
    
    // ================================
    // 依赖库配置
    // ================================
    libraryDependencies ++= Seq(
      
      // ================================
      // Pekko 核心依赖
      // ================================
      "org.apache.pekko" %% "pekko-actor-typed"              % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-typed"            % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed"   % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-tools"            % pekkoVersion,
      "org.apache.pekko" %% "pekko-discovery"                % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson"    % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream"                   % pekkoVersion,

      // ================================
      // Pekko Management / Cluster Bootstrap
      // ================================
      "org.apache.pekko" %% "pekko-management-cluster-http"      % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-discovery-kubernetes-api"     % pekkoManagementVersion,
      
      // ================================
      // Pekko 持久化 - Event Sourcing
      // ================================
      "org.apache.pekko" %% "pekko-persistence-typed"  % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-query"  % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-jdbc"   % pekkoPersistenceJdbcVersion,
      "com.github.alonsodomin.cron4s" %% "cron4s-core" % "0.8.2",
      
      // ================================
      // Pekko HTTP - REST API
      // ================================
      "org.apache.pekko" %% "pekko-http"            % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
      
      // ================================
      // Pekko Connectors - 数据集成
      // ================================
      // Only the historical Slick examples/tests use this connector; JDBC persistence supplies Slick core.
      "org.apache.pekko" %% "pekko-connectors-slick" % pekkoConnectorsVer % Test,
      "org.apache.pekko" %% "pekko-connectors-kafka" % pekkoConnectorsVer,
      
      // ================================
      // MySQL连接器依赖
      // ================================
      "com.mysql"        % "mysql-connector-j"  % "8.0.33",        // MySQL JDBC驱动
      "com.zaxxer"       % "HikariCP"           % "5.0.1",         // 连接池
      "io.debezium"      % "debezium-api"             % debeziumVersion,
      "io.debezium"      % "debezium-embedded"        % debeziumVersion,
      "io.debezium"      % "debezium-connector-mysql" % debeziumVersion,
      "io.debezium"      % "debezium-storage-jdbc"    % debeziumVersion,
      
      // ================================
      // JSON 处理
      // ================================
      "io.spray" %% "spray-json" % sprayJsonVersion,
      
      // Jackson显式依赖 - 确保版本一致性
      "com.fasterxml.jackson.core"   % "jackson-databind"     % jacksonVersion,
      "com.fasterxml.jackson.core"   % "jackson-core"         % jacksonVersion,
      "com.fasterxml.jackson.core"   % "jackson-annotations"  % jacksonVersion,
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,
      
      // ================================
      // 监控和指标 - Prometheus
      // ================================
      "io.prometheus" % "simpleclient"         % prometheusVersion,
      "io.prometheus" % "simpleclient_hotspot" % prometheusVersion,
      "io.prometheus" % "simpleclient_common"  % prometheusVersion,
      
      // ================================
      // SQL解析 - Apache Calcite
      // ================================
      "org.apache.calcite" % "calcite-core"   % calciteVersion exclude ("org.slf4j", "slf4j-api") exclude ("commons-logging", "commons-logging"),
      "org.apache.calcite" % "calcite-server" % calciteVersion exclude ("org.slf4j", "slf4j-api") exclude ("commons-logging", "commons-logging"),
      
      // ================================
      // 测试依赖
      // ================================
      "org.apache.pekko"  %% "pekko-stream-testkit"      % pekkoVersion     % Test,
      "org.apache.pekko"  %% "pekko-multi-node-testkit"  % pekkoVersion     % Test,
      "org.apache.pekko"  %% "pekko-actor-testkit-typed" % pekkoVersion     % Test,
      "org.apache.pekko"  %% "pekko-persistence-testkit" % pekkoVersion     % Test,
      "org.apache.pekko"  %% "pekko-http-testkit"        % pekkoHttpVersion % Test,
      "org.scalatestplus" %% "scalacheck-1-17"           % scalatestPlusVersion % Test,
      "org.scalacheck"    %% "scalacheck"                % scalacheckVersion    % Test,
      "com.h2database"     % "h2"                        % h2Version        % Runtime,
      "com.h2database"     % "h2"                        % h2Version        % Test
    )
  )

// ================================
// 根项目配置
// ================================
lazy val root = (project in file("."))
  .settings(commonSettings)
  .aggregate(
    pekkoServer
  )
  .settings(
    name := projectName,
    publish := {},        // 根项目不发布
    publishLocal := {},   // 根项目不本地发布
    
    // ================================
    // 自定义任务别名 - 提供便捷命令
    // ================================
    addCommandAlias("runServer", "pekko-server/run"),
    addCommandAlias("testAll", "test"),
    addCommandAlias("dockerBuild", "pekko-server/Docker/publishLocal"),
    addCommandAlias("dockerPublish", "pekko-server/Docker/publish"),
    addCommandAlias("checkDeps", "pekko-server/dependencyTree"),
    addCommandAlias("cleanAll", "clean; pekko-server/clean")
  )
