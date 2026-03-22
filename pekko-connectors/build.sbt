name := "pekko-connectors"
version := "0.1.0"

libraryDependencies ++= Seq(
  // 数据库连接池
  "com.zaxxer" % "HikariCP" % "5.1.0",
  
  // 数据库驱动
  "mysql" % "mysql-connector-java" % "8.0.33",
  "org.postgresql" % "postgresql" % "42.7.0",
  
  // JSON (复用spray-json)
  "io.spray" %% "spray-json" % "1.3.6",
  
  // 测试
  "org.scalatest" %% "scalatest" % "3.2.17" % Test
)
