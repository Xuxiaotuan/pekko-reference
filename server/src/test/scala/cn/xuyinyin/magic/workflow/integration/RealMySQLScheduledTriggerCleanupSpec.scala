package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.scheduling.ScheduledTriggerCleanupChecks
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import org.scalatest.wordspec.AnyWordSpec
import java.nio.file.{Files,Path}
import spray.json._

/** SQL/runtime cache checks; this suite alone makes no Journal/Sink end-to-end claim. */
class RealMySQLScheduledTriggerCleanupSpec extends AnyWordSpec {
  import CdcJournalWindowSupport._
  "real MySQL scheduled trigger lifecycle" should {
    ScheduledTriggerCleanupChecks.scenarios.foreach { scenario =>
      s"reconcile cache, quota and durable trigger for $scenario" taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_MYSQL_SNAPSHOT_TAKEOVER").contains("1")) cancel("external_blocked: explicit isolated MySQL flag required")
        val settings = Settings.environment()
        val suffix = java.util.UUID.randomUUID().toString.replace("-","").take(12)
        val schema = "pekko_test_cdc_window_"+suffix
        val dir = Path.of("target","scheduled-trigger-cleanup",scenario+"-"+suffix).toAbsolutePath
        Files.createDirectories(dir)
        initialize(settings,schema)
        withConnection(settings,schema) { c =>
          Vector("db/mysql/pekko-table-scheduling-schema.sql","db/mysql/pekko-snapshot-ownership-schema.sql").foreach { path =>
            val in = getClass.getClassLoader.getResourceAsStream(path)
            val sql = try scala.io.Source.fromInputStream(in).mkString finally in.close()
            sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(executeSql(c,_))
          }
        }
        val hc = new HikariConfig();hc.setJdbcUrl(settings.url(schema));hc.setUsername(settings.user);hc.setPassword(settings.password);hc.setMaximumPoolSize(4)
        val ds = new HikariDataSource(hc)
        try {
          val result = ScheduledTriggerCleanupChecks.run(ds,scenario)
          withConnection(settings,schema) { c =>
            Vector("pekko_table_tasks","pekko_snapshot_schedule_triggers","pekko_snapshot_writer_permissions","pekko_snapshot_ownership_audit").foreach { table =>
              write(dir,table+".json",query(c,"SELECT * FROM "+table))
            }
            write(dir,"server.json",query(c,"SELECT @@server_uuid AS server_uuid,VERSION() AS version"))
          }
          write(dir,"result.json",JsObject(result.fields ++ Map("schema"->JsString(schema),
            "runtime"->JsString(sys.env.getOrElse("SNAPSHOT_TAKEOVER_RUNTIME","unfrozen")))))
        } finally ds.close()
      }
    }
  }
}
