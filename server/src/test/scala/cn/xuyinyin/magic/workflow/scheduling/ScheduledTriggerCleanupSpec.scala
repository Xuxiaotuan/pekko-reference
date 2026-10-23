package cn.xuyinyin.magic.workflow.scheduling

import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.scalatest.wordspec.AnyWordSpec

class ScheduledTriggerCleanupSpec extends AnyWordSpec {
  "scheduled trigger lifecycle" should {
    ScheduledTriggerCleanupChecks.scenarios.foreach { scenario =>
      s"preserve durable intent and clean only the confirmed grant for $scenario" in {
        val hc = new HikariConfig()
        hc.setJdbcUrl("jdbc:h2:mem:trigger-"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1")
        hc.setUsername("sa");hc.setMaximumPoolSize(4)
        val ds = new HikariDataSource(hc)
        try {
          val c = ds.getConnection
          try Vector("db/h2/pekko-table-scheduling-schema.sql","db/h2/pekko-snapshot-ownership-schema.sql").foreach { path =>
            val in = getClass.getClassLoader.getResourceAsStream(path)
            val sql = try scala.io.Source.fromInputStream(in).mkString finally in.close()
            sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { query =>
              val s = c.createStatement();try s.execute(query) finally s.close()
            }
          } finally c.close()
          ScheduledTriggerCleanupChecks.run(ds,scenario)
        } finally ds.close()
      }
    }
  }
}
