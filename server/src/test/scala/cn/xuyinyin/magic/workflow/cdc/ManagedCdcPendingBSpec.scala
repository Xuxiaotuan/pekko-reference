package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig,MySQLCdcSourceNode,MySQLCdcStateConfig}
import cn.xuyinyin.magic.connectors.mysql.source.capture.MySQLCaptureStore
import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.scheduling.{SchedulingLimits,SnapshotOwnerIdentity,SnapshotOwnerJdbc,TableSchedulingStore}
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import java.nio.file.{Files,Path}
import java.sql.DriverManager
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

/** Real D and marker transactions; no guardian recovery or target execution. Run alone. */
class ManagedCdcPendingBSpec extends AnyWordSpec with Matchers {
  "pending B detection" should {
    "distinguish rolled back and committed markers at the original durable D" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CDC_PENDING_B_PROBE").contains("1"))cancel("isolated dual MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val password=sys.env("CDC_MANAGED_TEST_PASSWORD");val controlUser=sys.env("CDC_MANAGED_TEST_USER")
      val stateUrl=sys.env("CDC_PENDING_STATE_URL");val metadataUrl=sys.env("CDC_PENDING_METADATA_URL")
      val sourcePort=sys.env("CDC_PENDING_SOURCE_PORT").toInt;val schema=sys.env("CDC_PENDING_SCHEMA")
      val capture=sys.env("CDC_PENDING_CAPTURE");val sourceUser=sys.env("CDC_PENDING_SOURCE_USER");val sdkUser=sys.env("CDC_PENDING_SDK_USER")
      val sourceUuid=sys.env("CAPTURE_EXPECT_SOURCE_UUID");val stateUuid=sys.env("CAPTURE_EXPECT_STATE_UUID")
      require(schema.startsWith("pekko_test_cdc_window_") && sourcePort!=3306 && stateUrl.contains(schema))
      val hc=new HikariConfig();hc.setJdbcUrl(metadataUrl);hc.setUsername(controlUser);hc.setPassword(password);hc.setMaximumPoolSize(4)
      val ds=new HikariDataSource(hc)
      try {
        val scheduling=new TableSchedulingStore(ds,SchedulingLimits(2,1,8,1,1048576,8),ownershipEnabled=true)
        val identity=SnapshotOwnerIdentity("candidate-"+UUID.randomUUID(),"probe",ProcessHandle.current().pid(),ProcessHandle.current().info().startInstant().orElseThrow().toString)
        scheduling.ownership.get.registerOwner(identity)
        val sourceUrl=s"jdbc:mysql://127.0.0.1:$sourcePort/$schema?useSSL=false&allowPublicKeyRetrieval=true"
        SnapshotOwnerJdbc.install(SnapshotOwnerJdbc.Context(identity,scheduling,Map(SnapshotOwnerJdbc.endpoint(sourceUrl)->sourceUuid,SnapshotOwnerJdbc.endpoint(stateUrl)->stateUuid),()=>()))
        val source=MySQLCdcSourceConfig(capture,"127.0.0.1",sourcePort,schema,"pekko_capture_control",sourceUser,password,901,128,100)
        val control=MySQLCdcStateConfig(stateUrl,controlUser,password,"capture_offsets","capture_history",0)
        val sdk=control.copy(username=sdkUser)
        val cfg=CdcCaptureSettings(capture,source,sdk,control,sourceUuid,stateUuid)
        def state()=DriverManager.getConnection(stateUrl,sdkUser,password)
        val log=new MySQLCaptureStore(()=>state(),capture,sourceUuid,128L*1024*1024)
        val table=MySQLCdcSourceNode.connectorStateTables(sdk,capture)._1
        val c=state();val offset=try {val q=c.createStatement();try {val r=q.executeQuery(s"SELECT offset_val FROM `$table` WHERE offset_val IS NOT NULL");try {require(r.next());r.getString(1)}finally r.close()}finally q.close()}finally c.close()
        val task=CdcTaskRequest("pending_probe_"+UUID.randomUUID().toString.take(8),capture,"source_rows","probe1","target_probe")
        val schemaId=log.schema(task.table).get.id
        log.registerAnchored(task.taskId,task.table,task.generation,schemaId,offset)
        val sourceConnection=DriverManager.getConnection(sourceUrl,sourceUser,password)
        var connectionId=0L;var heldTransactions=0L
        try {
          sourceConnection.setAutoCommit(false)
          val id=sourceConnection.createStatement();try {val r=id.executeQuery("SELECT CONNECTION_ID()");try {r.next();connectionId=r.getLong(1)}finally r.close()}finally id.close()
          def insert():Unit={val q=sourceConnection.prepareStatement("INSERT INTO pekko_capture_control VALUES(?,?,?,?)")
            try {q.setString(1,log.markerId(task.taskId,stop=false));q.setString(2,"SUBSCRIBE");q.setString(3,task.table);q.setString(4,task.generation);q.executeUpdate();()}finally q.close()}
          insert()
          val observer=DriverManager.getConnection(sourceUrl,controlUser,password)
          try {val q=observer.prepareStatement("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=?");try {q.setLong(1,connectionId);val r=q.executeQuery();try {r.next();heldTransactions=r.getLong(1)}finally r.close()}finally q.close()}finally observer.close()
          heldTransactions shouldBe 1L
          CdcManagedPendingB.verify(cfg,task.taskId).markerPresent shouldBe false
          sourceConnection.rollback()
          CdcManagedPendingB.verify(cfg,task.taskId).markerPresent shouldBe false
          insert();sourceConnection.commit()
          CdcManagedPendingB.verify(cfg,task.taskId).markerPresent shouldBe true
        }finally sourceConnection.close()
        val result=JsObject("status"->JsString("PASS"),"scope"->JsString("real MySQL D and marker transaction only; no guardian, grant, Journal transition or Sink"),
          "capture"->JsString(capture),"taskId"->JsString(task.taskId),"actualD"->offset.parseJson,"sourceConnection"->JsNumber(connectionId),
          "heldTransactions"->JsNumber(heldTransactions),"heldVisible"->JsBoolean(false),"rolledBackVisible"->JsBoolean(false),"committedVisible"->JsBoolean(true))
        val output=Path.of(sys.env("CDC_PENDING_EVIDENCE"));Files.createDirectories(output);Files.writeString(output.resolve("result.json"),result.prettyPrint)
      } finally ds.close()
    }
  }
}
