package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.testkit.STSpec
import java.sql.DriverManager
import java.util.{Properties, UUID}

class MySQLCdcStreamingStateSpec extends STSpec {
  "group protocol streaming baseline" should {
    "reject unfinished snapshots and missing offsets instead of starting at current binlog" in {
      val url = "jdbc:h2:mem:group_offset_" + UUID.randomUUID().toString.replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1"
      val connection = DriverManager.getConnection(url, "sa", "")
      val properties = new Properties()
      properties.setProperty("offset.storage.jdbc.connection.url", url)
      properties.setProperty("offset.storage.jdbc.connection.user", "sa")
      properties.setProperty("offset.storage.jdbc.connection.password", "")
      properties.setProperty("offset.storage.jdbc.table.name", "offsets")
      val node = new MySQLCdcSourceNode {
        def check(): Unit = verifyStreamingState(properties)
      }
      val statement = connection.createStatement()
      try {
        statement.execute("CREATE TABLE offsets(offset_val VARCHAR(2048))")
        intercept[IllegalArgumentException](node.check())
        statement.execute("INSERT INTO offsets VALUES ('{\"file\":\"binlog.000001\",\"pos\":100,\"snapshot\":\"INITIAL\"}')")
        intercept[IllegalArgumentException](node.check())
        statement.execute("UPDATE offsets SET offset_val='{\"file\":\"binlog.000001\",\"pos\":100}'")
        node.check()
      } finally { statement.close(); connection.close() }
    }
  }
}
