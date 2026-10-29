package cn.xuyinyin.magic.workflow.integration

import java.sql.DriverManager

/** Test-only old JVM that remains alive without a database connection until released. */
object CaptureLateReconnectWorker {
  def main(args:Array[String]):Unit={
    val port=args(0).toInt;val schema=args(1)
    println("WINDOW {\"kind\":\"READY\"}");System.out.flush()
    require(scala.io.StdIn.readLine()=="CONNECT")
    val c=DriverManager.getConnection(s"jdbc:mysql://127.0.0.1:$port/$schema?useSSL=false&allowPublicKeyRetrieval=true",
      sys.env("TEST_LATE_SOURCE_USER"),sys.env("TEST_LATE_SOURCE_PASSWORD"))
    try {
      val q=c.createStatement()
      try {
        val r=q.executeQuery("SELECT CONNECTION_ID()");r.next();val id=r.getLong(1);r.close()
        q.executeUpdate("INSERT INTO marker VALUES('late-writer','SUBSCRIBE')")
        println(s"WINDOW {\"kind\":\"WRITTEN\",\"connectionId\":$id}");System.out.flush()
        scala.io.StdIn.readLine()
      }finally q.close()
    }finally c.close()
  }
}
