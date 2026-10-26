package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.cdc.{CdcPlatformLimits, CdcTaskRequest, CdcTaskStore}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import java.sql.DriverManager
import java.util.UUID
import spray.json._

/** Cold index cost only: these metadata rows are not physical MySQL source tables. */
class CdcPlatformMetadataSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._
  "the persistent CDC cold index" should {
    "page cold metadata with bounded pages and no Reader, Actor or execution grant" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_CDC_PLATFORM_METADATA").contains("1")) cancel("explicit disposable metadata experiment required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty), "exclusive slot lease required")
      val source = Settings.environment(); val state = source.copy(port = sys.env("CAPTURE_STATE_PORT").toInt)
      require(!Set(3306, 13365, 13366).contains(state.port) && !Set(3306, 13365, 13366).contains(source.port))
      val sourceIdentity = withConnection(source, "")(query(_, "SELECT @@server_uuid AS uuid,@@port AS port,@@datadir AS datadir"))
      val identity = withConnection(state, "")(query(_, "SELECT @@server_uuid AS uuid,@@port AS port,@@datadir AS datadir,VERSION() AS version"))
      sourceIdentity.elements.head.asJsObject.fields("uuid") shouldBe JsString(sys.env("CAPTURE_EXPECT_SOURCE_UUID"))
      identity.elements.head.asJsObject.fields("uuid") shouldBe JsString(sys.env("CAPTURE_EXPECT_STATE_UUID"))
      sourceIdentity.elements.head.asJsObject.fields("uuid") should not be identity.elements.head.asJsObject.fields("uuid")
      val count = sys.env.getOrElse("CDC_PLATFORM_METADATA_ROWS", "700000").toInt
      require(count > 0 && count <= 700000)
      val suffix = UUID.randomUUID().toString.replace("-", "").take(12); val schema = "pekko_test_cdc_window_" + suffix
      val dir = Path.of(sys.env.getOrElse("CDC_PLATFORM_EVIDENCE", "target/cdc-platform-matrix"), "H01-metadata-" + suffix).toAbsolutePath
      Files.createDirectories(dir)
      write(dir, "environment.json", JsObject("source" -> sourceIdentity, "state" -> identity,
        "rowsRequested" -> JsNumber(count), "scope" -> JsString("cold metadata only, no physical source tables"),
        "sourceManifestSha256" -> JsString(sys.env.getOrElse("BOOTSTRAP_SOURCE_MANIFEST_SHA256", "unfrozen"))))
      initialize(state, schema)
      def open() = DriverManager.getConnection(state.url(schema) + "&rewriteBatchedStatements=true&connectTimeout=5000&socketTimeout=30000", state.user, state.password)
      val store = new CdcTaskStore(() => open(), CdcPlatformLimits())
      store.initialize()
      val clock = ManagementFactory.getThreadMXBean
      val dataDir = Path.of(identity.elements.head.asJsObject.fields("datadir").convertTo[String]).toRealPath()
      var diskScanRetries = 0L
      def dataBytes(): Long = {
        val result=CdcMetadataDiskSize.measure(dataDir,()=>{
          val files = Files.walk(dataDir)
          try { val it = files.iterator(); var total = 0L; while (it.hasNext) { val p = it.next(); if (Files.isRegularFile(p)) total += Files.size(p) }; total }
          finally files.close()
        })
        diskScanRetries+=result.retries;result.bytes
      }
      def binaryLogs(): JsArray = withConnection(state, "")(query(_, "SHOW BINARY LOGS"))
      val beforeLogs = binaryLogs(); val beforeBytes = dataBytes()
      def freeBytes = Files.getFileStore(dataDir).getUsableSpace
      require(freeBytes >= 5L * 1024 * 1024 * 1024, "metadata fixture disk headroom below 5GiB")
      def sample(rows: Long): JsObject = {
        val memory = Runtime.getRuntime
        val actualBytes = dataBytes(); val remaining = freeBytes
        require(actualBytes - beforeBytes <= 2L * 1024 * 1024 * 1024 && remaining >= 5L * 1024 * 1024 * 1024,
          "metadata disk stop threshold reached; fixture retained without DROP or purge")
        JsObject("atMillis" -> JsNumber(System.currentTimeMillis()), "rows" -> JsNumber(rows),
          "heapUsedBytes" -> JsNumber(memory.totalMemory() - memory.freeMemory()), "liveThreads" -> JsNumber(clock.getThreadCount),
          "activeGrants" -> JsNumber(store.counts.values.sum), "dataDirBytes" -> JsNumber(actualBytes), "freeBytes" -> JsNumber(remaining),
          "diskScanRetries"->JsNumber(diskScanRetries),
          "connections" -> withConnection(state, schema)(query(_, "SELECT ID,USER,HOST,DB,COMMAND FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID()")),
          "transactions" -> withConnection(state, schema)(query(_, "SELECT trx_id,trx_state,trx_mysql_thread_id FROM information_schema.INNODB_TRX")))
      }
      var samples = Vector(sample(0)); val began = System.nanoTime()
      var insertionMillis = 0L
      try {
        val connection = open(); connection.setAutoCommit(false)
        try {
          val statement = connection.prepareStatement("INSERT INTO pekko_cdc_tasks_v1(task_id,capture_id,request_id,table_name,generation,target_table,definition_hash,desired_state,control_revision,phase,journal_revision,capture_readiness,runtime_health,release_status,observed_at,due_at,work_pool,schedulable) VALUES(?,?,?,?,?,?,?,'RUNNING',1,'QUEUED',0,'UNKNOWN','WAITING','RELEASED',?,?,'SNAPSHOT',TRUE)")
          try {
            var row = 1
            while (row <= count) {
              val request = CdcTaskRequest("r" + row, "cold_metadata", "table_" + row, "g1", "target_" + row)
              statement.setString(1, request.taskId); statement.setString(2, request.captureId); statement.setString(3, request.requestId)
              statement.setString(4, request.table); statement.setString(5, request.generation); statement.setString(6, request.targetTable)
              statement.setString(7, request.fingerprint); statement.setLong(8, System.currentTimeMillis()); statement.setLong(9, 0L)
              statement.addBatch()
              if (row % 1000 == 0 || row == count) { statement.executeBatch(); connection.commit() }
              if (row % 50000 == 0) { samples :+= sample(row); write(dir, "progress.json", JsObject("samples" -> JsArray(samples))) }
              row += 1
            }
          } finally statement.close()
        } finally connection.close()
        insertionMillis = (System.nanoTime() - began) / 1000000L
        val exact = withConnection(state, schema)(query(_, "SELECT COUNT(*) AS n FROM pekko_cdc_tasks_v1")).elements.head.asJsObject.fields("n").convertTo[String].toLong
        exact shouldBe count.toLong
        val scanStart = System.nanoTime(); var after = Option.empty[String]; var seen = 0L; var pages = 0L; var done = false
        var last = ""
        var cursors = Vector.empty[(String, Option[String])]
        while (!done) {
          if (seen == 0L) cursors :+= "first" -> after
          if (seen <= count / 2 && seen + 100 > count / 2) cursors :+= "middle" -> after
          if (seen + 100 >= count) cursors :+= "last" -> after
          val page = store.listAfter(after, 100)
          page.items.size should be <= 100
          page.items.foreach { value =>
            value.taskId.compareTo(last) should be > 0
            value.phase shouldBe "QUEUED"
            value.executionId shouldBe None
            last = value.taskId
          }
          seen += page.items.size; pages += 1
          after = page.nextAfter; done = after.isEmpty
        }
        seen shouldBe count.toLong
        store.counts.values.sum shouldBe 0L
        store.ownerStatus shouldBe "STOPPED"
        val scanMillis = (System.nanoTime() - scanStart) / 1000000L
        val plans = cursors.map { case (position, cursor) => withConnection(state, schema) { c =>
          val q = c.prepareStatement("EXPLAIN SELECT * FROM pekko_cdc_tasks_v1 WHERE task_id>? ORDER BY task_id LIMIT ?")
          q.setString(1, cursor.getOrElse("")); q.setInt(2, 101)
          val rows = q.executeQuery(); val metadata = rows.getMetaData; val values = Vector.newBuilder[JsValue]
          try { while (rows.next()) values += JsObject((1 to metadata.getColumnCount).map(i => metadata.getColumnLabel(i) -> Option(rows.getString(i)).map(JsString(_)).getOrElse(JsNull)).toMap) }
          finally { rows.close(); q.close() }
          JsObject("position" -> JsString(position), "after" -> cursor.map(JsString(_)).getOrElse(JsNull), "plan" -> JsArray(values.result()))
        } }
        val claimPlan = withConnection(state, schema) { c =>
          val q = c.prepareStatement("EXPLAIN SELECT * FROM pekko_cdc_tasks_v1 WHERE capture_id=? AND work_pool=? AND schedulable=TRUE AND due_at<=? ORDER BY due_at,task_id LIMIT 1 FOR UPDATE")
          q.setString(1, "cold_metadata"); q.setString(2, "SNAPSHOT"); q.setLong(3, System.currentTimeMillis())
          val rows = q.executeQuery(); val metadata = rows.getMetaData; val values = Vector.newBuilder[JsValue]
          try { while (rows.next()) values += JsObject((1 to metadata.getColumnCount).map(i => metadata.getColumnLabel(i) -> Option(rows.getString(i)).map(JsString(_)).getOrElse(JsNull)).toMap) }
          finally { rows.close(); q.close() }
          JsArray(values.result())
        }
        samples :+= sample(seen)
        val quietStart = System.nanoTime()
        (1 to 12).foreach { _ => Thread.sleep(5000L); samples :+= sample(seen); write(dir, "quiet-progress.json", JsObject("samples" -> JsArray(samples))) }
        val resources = withConnection(state, schema)(query(_, "SELECT ID,USER,HOST,DB,COMMAND FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID()"))
        val tableSize = withConnection(state, schema)(query(_, "SELECT TABLE_NAME,TABLE_ROWS,DATA_LENGTH,INDEX_LENGTH FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('pekko_cdc_tasks_v1','pekko_cdc_grants_v1')"))
        write(dir, "result.json", JsObject("status" -> JsString("METADATA_ONLY"), "rows" -> JsNumber(seen), "pages" -> JsNumber(pages),
          "exactDatabaseRows" -> JsNumber(exact), "insertMillis" -> JsNumber(insertionMillis), "pageScanMillis" -> JsNumber(scanMillis),
          "quietObservationMillis" -> JsNumber((System.nanoTime() - quietStart) / 1000000L), "queryPlans" -> JsArray(plans),
          "claimQueryPlan" -> claimPlan,
          "samples" -> JsArray(samples), "stateIdentity" -> identity, "connections" -> resources, "estimatedTableBytes" -> tableSize,
          "binaryLogsBefore" -> beforeLogs, "binaryLogsAfter" -> binaryLogs(), "dataDirectoryBytesBefore" -> JsNumber(beforeBytes),
          "activeGrants" -> JsNumber(0), "readerCreated" -> JsBoolean(false), "actorSystemCreated" -> JsBoolean(false),
          "sourceTablesCreated" -> JsNumber(0), "sourceManifestSha256" -> JsString(sys.env.getOrElse("BOOTSTRAP_SOURCE_MANIFEST_SHA256", "unfrozen"))))
      } catch { case e: Throwable =>
        write(dir, "failure.json", JsObject("class" -> JsString(e.getClass.getSimpleName), "insertMillis" -> JsNumber(insertionMillis),
          "elapsedMillis"->JsNumber((System.nanoTime()-began)/1000000L),
          "samples" -> JsArray(samples), "message" -> JsString(Option(e.getMessage).getOrElse("").replace(state.password, "[REDACTED]"))))
        throw e
      }
    }
  }
}
