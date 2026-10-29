package cn.xuyinyin.magic.workflow.integration

import cn.xuyinyin.magic.connectors.mysql.source.capture.CaptureResourceObserver
import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import java.nio.file.{Files, Path}
import java.sql.DriverManager
import java.util.UUID
import scala.util.Try
import scala.concurrent.duration._
import spray.json._

/** B08 component probe: revoke only a newly-created observer on an isolated slot. */
class CaptureObserverPrivilegeSpec extends AnyWordSpec with Matchers {
  import CdcJournalWindowSupport._

  "a live capture release observer" should {
    "show that an idle old JVM can reconnect after a clean database observation" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CAPTURE_ADMISSION_MATRIX").contains("1"))cancel("exclusive dual-MySQL slot required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
      require(source.port!=state.port && !Set(3306,13365,13366).contains(source.port) && !Set(3306,13365,13366).contains(state.port))
      def uuid(s:Settings)=withConnection(s,"")(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
      val su=uuid(source);val tu=uuid(state);require(su==sys.env("CAPTURE_EXPECT_SOURCE_UUID") && tu==sys.env("CAPTURE_EXPECT_STATE_UUID"))
      val suffix=UUID.randomUUID().toString.replace("-","").take(12);val schema="pekko_test_cdc_window_"+suffix
      val user="late_"+suffix;val secret=UUID.randomUUID().toString
      val out=Path.of(sys.env("CAPTURE_ADMISSION_EVIDENCE")).resolve("late_writer_"+suffix);Files.createDirectories(out)
      withConnection(source,""){c=>executeSql(c,s"CREATE DATABASE `$schema`");executeSql(c,s"CREATE USER '$user'@'127.0.0.1' IDENTIFIED BY '$secret'");executeSql(c,s"GRANT ALL ON `$schema`.* TO '$user'@'127.0.0.1'")}
      withConnection(source,schema)(executeSql(_,"CREATE TABLE marker (operation_id VARCHAR(64) PRIMARY KEY, action VARCHAR(16) NOT NULL) ENGINE=InnoDB"))
      val b=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString,"-Xmx128m","-cp",System.getProperty("java.class.path"),
        CaptureLateReconnectWorker.getClass.getName.stripSuffix("$"),source.port.toString,schema)
      b.redirectErrorStream(true);b.environment().put("TEST_LATE_SOURCE_USER",user);b.environment().put("TEST_LATE_SOURCE_PASSWORD",secret)
      val child=new Child(b.start(),out.resolve("old-worker.log"))
      val observer=CaptureResourceObserver.exclusiveAccounts(()=>DriverManager.getConnection(source.url(""),source.user,source.password),
        ()=>DriverManager.getConnection(state.url(""),state.user,state.password),user,"unused_"+suffix,su,tu)
      try {
        child.awaitEvent("READY");observer.beforeStart("new-attempt",0L)
        val before=observer.sample("new-attempt",0L,includeOwner=true)
        before.status shouldBe "CONFIRMED"
        child.send("CONNECT");val written=child.awaitEvent("WRITTEN")
        val after=observer.sample("new-attempt",0L,includeOwner=true)
        val rows=withConnection(source,schema)(query(_,"SELECT operation_id,action FROM marker"))
        write(out,"result.json",JsObject("oldPid"->JsNumber(child.pid),"oldAlive"->JsBoolean(child.alive),"beforeStatus"->JsString(before.status),
          "afterStatus"->JsString(after.status),"oldConnectionId"->written.fields("connectionId"),"observedSessions"->JsArray(after.sourceSessions.map(JsNumber(_))),
          "committedRows"->rows,"scope"->JsString("independent unfenced old JVM; observer component only, no Journal or Sink")))
        child.alive shouldBe true;after.status shouldBe "UNKNOWN"
        after.sourceSessions should contain (written.fields("connectionId").convertTo[Long])
        rows.elements.size shouldBe 1
      }finally {child.kill();observer.close()}
    }
    for(outcome<-Vector("commit","rollback","late-commit")) {
      s"observe an actual marker transaction before and after $outcome" taggedAs ExternalIntegration in {
        if(!sys.env.get("RUN_CAPTURE_ADMISSION_MATRIX").contains("1"))cancel("exclusive dual-MySQL slot required")
        require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
        val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
        require(source.port!=state.port && !Set(3306,13365,13366).contains(source.port) && !Set(3306,13365,13366).contains(state.port))
        def uuid(s:Settings)=withConnection(s,"")(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
        val sourceUuid=uuid(source);val stateUuid=uuid(state)
        require(sourceUuid==sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateUuid==sys.env("CAPTURE_EXPECT_STATE_UUID"))
        val suffix=UUID.randomUUID().toString.replace("-","").take(12);val schema="pekko_test_cdc_window_"+suffix
        val user="tx_"+suffix;val secret=UUID.randomUUID().toString
        val out=Path.of(sys.env("CAPTURE_ADMISSION_EVIDENCE")).resolve("marker_tx_"+outcome+"_"+suffix);Files.createDirectories(out)
        withConnection(source,""){c=>executeSql(c,s"CREATE DATABASE `$schema`");executeSql(c,s"CREATE USER '$user'@'127.0.0.1' IDENTIFIED BY '$secret'");executeSql(c,s"GRANT ALL ON `$schema`.* TO '$user'@'127.0.0.1'")}
        withConnection(source,schema)(executeSql(_,"CREATE TABLE marker (operation_id VARCHAR(64) PRIMARY KEY, action VARCHAR(16) NOT NULL) ENGINE=InnoDB"))
        def observer=CaptureResourceObserver.exclusiveAccounts(()=>DriverManager.getConnection(source.url(""),source.user,source.password),
          ()=>DriverManager.getConnection(state.url(""),state.user,state.password),user,"unused_"+suffix,sourceUuid,stateUuid)
        def sourceRows=withConnection(source,schema)(query(_,"SELECT operation_id,action FROM marker ORDER BY operation_id"))
        def sessions=withConnection(source,"")(query(_,s"SELECT ID,USER,COMMAND,STATE FROM information_schema.PROCESSLIST WHERE USER='$user'"))
        def transactions=withConnection(source,"")(query(_,"SELECT trx_id,trx_state,trx_mysql_thread_id FROM information_schema.INNODB_TRX"))
        var transaction:java.sql.Connection=null
        try {
          transaction=DriverManager.getConnection(source.url(schema),user,secret);transaction.setAutoCommit(false)
          val id=query(transaction,"SELECT CONNECTION_ID() AS id").elements.head.asJsObject.fields("id")
          executeSql(transaction,"INSERT INTO marker VALUES('operation-1','SUBSCRIBE')")
          awaitCondition("the marker transaction is visible in InnoDB",30.seconds){
            transactions.elements.exists(_.asJsObject.fields("trx_mysql_thread_id")==id)
          }
          val before=JsObject("connectionId"->id,"sessions"->sessions,"transactions"->transactions,"visibleRows"->sourceRows)
          if(outcome=="late-commit"){
            val blocked=observer
            val refusal=Try(blocked.beforeStart("attempt-before-resolution",0L))
            write(out,"held.json",JsObject("before"->before,"observerRefused"->JsBoolean(refusal.isFailure),"errorClass"->refusal.failed.toOption.map(e=>JsString(e.getClass.getName)).getOrElse(JsNull),
              "reason"->refusal.failed.toOption.map(e=>JsString(Option(e.getMessage).getOrElse(""))).getOrElse(JsNull)))
            refusal.isFailure shouldBe true
            refusal.failed.get.getMessage should include ("capture account has unresolved previous sessions or transactions")
            Try(blocked.close())
          }
          if(outcome=="rollback")transaction.rollback() else transaction.commit()
          transaction.close();transaction=null
          awaitCondition("the original account session and transaction are actually gone",30.seconds){
            sessions.elements.isEmpty && transactions.elements.isEmpty
          }
          val allowed=observer
          try {
            allowed.beforeStart("attempt-after-resolution",0L)
            val proof=allowed.sample("attempt-after-resolution",0L,includeOwner=true)
            val rows=sourceRows
            write(out,"result.json",JsObject("outcome"->JsString(outcome),"before"->before,"afterSessions"->sessions,
              "afterTransactions"->transactions,"visibleRows"->rows,"observerStatus"->JsString(proof.status),
              "scope"->JsString("real MySQL transaction and observer component; no Reader, Journal, Sink or owner isolation")))
            proof.status shouldBe "CONFIRMED"
            rows.elements.size shouldBe (if(outcome=="rollback")0 else 1)
          } finally allowed.close()
        } finally if(transaction!=null){Try(transaction.rollback());Try(transaction.close())}
      }
    }
    "retain UNKNOWN for an unowned session sharing the capture account" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CAPTURE_ADMISSION_MATRIX").contains("1"))cancel("exclusive dual-MySQL slot required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
      require(source.port!=state.port && !Set(3306,13365,13366).contains(source.port) && !Set(3306,13365,13366).contains(state.port))
      def uuid(s:Settings)=withConnection(s,"")(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
      val sourceUuid=uuid(source);val stateUuid=uuid(state)
      require(sourceUuid==sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateUuid==sys.env("CAPTURE_EXPECT_STATE_UUID"))
      val suffix=UUID.randomUUID().toString.replace("-","").take(12);val user="amb_"+suffix;val secret=UUID.randomUUID().toString
      val out=Path.of(sys.env("CAPTURE_ADMISSION_EVIDENCE")).resolve("observer_ambiguous_"+suffix);Files.createDirectories(out)
      withConnection(source,"")(executeSql(_,s"CREATE USER '$user'@'127.0.0.1' IDENTIFIED BY '$secret'"))
      val observer=CaptureResourceObserver.exclusiveAccounts(()=>DriverManager.getConnection(source.url(""),source.user,source.password),
        ()=>DriverManager.getConnection(state.url(""),state.user,state.password),user,"unused_"+suffix,sourceUuid,stateUuid)
      var unrelated:java.sql.Connection=null
      try {
        observer.beforeStart("attempt",0L)
        unrelated=DriverManager.getConnection(source.url(""),user,secret)
        val id=query(unrelated,"SELECT CONNECTION_ID() AS id").elements.head.asJsObject.fields("id").convertTo[String].toLong
        val sample=observer.sample("attempt",0L,includeOwner=true)
        val alive=query(unrelated,"SELECT 1 AS alive").elements.nonEmpty
        write(out,"result.json",JsObject("case"->JsString("B08-unowned-same-account"),"status"->JsString(sample.status),
          "reason"->sample.reason.map(JsString(_)).getOrElse(JsNull),"connectionId"->JsNumber(id),
          "observedSourceSessions"->JsArray(sample.sourceSessions.map(JsNumber(_))),"unownedStillAlive"->JsBoolean(alive),
          "scope"->JsString("observer component; an unrelated test session shares the observed account; no Reader or Actor/Sink")))
        sample.status shouldBe "UNKNOWN";sample.sourceSessions should contain (id);alive shouldBe true
      }finally {if(unrelated!=null)unrelated.close();observer.close()}
    }
    "reject the original observer anchor after source restart" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CAPTURE_ADMISSION_MATRIX").contains("1"))cancel("exclusive dual-MySQL slot required")
      val gate=Path.of(sys.env.getOrElse("CAPTURE_SOURCE_RESTART_GATE",throw new IllegalStateException("explicit source restart gate required")))
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val source=Settings.environment();val state=source.copy(port=sys.env("CAPTURE_STATE_PORT").toInt)
      require(source.port!=state.port && !Set(3306,13365,13366).contains(source.port) && !Set(3306,13365,13366).contains(state.port))
      def uuid(s:Settings)=withConnection(s,"")(query(_,"SELECT @@server_uuid AS uuid")).elements.head.asJsObject.fields("uuid").convertTo[String]
      val sourceUuid=uuid(source);val stateUuid=uuid(state)
      require(sourceUuid==sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateUuid==sys.env("CAPTURE_EXPECT_STATE_UUID"))
      val suffix=UUID.randomUUID().toString.replace("-","").take(12)
      val captureUser="cap_"+suffix;val secret=UUID.randomUUID().toString
      val out=Path.of(sys.env("CAPTURE_ADMISSION_EVIDENCE")).resolve("observer_restart_"+suffix);Files.createDirectories(out)
      withConnection(source,"")(executeSql(_,s"CREATE USER '$captureUser'@'127.0.0.1' IDENTIFIED BY '$secret'"))
      var anchor:java.sql.Connection=null;var active:java.sql.Connection=null
      val observer=CaptureResourceObserver.exclusiveAccounts(
        ()=>{anchor=DriverManager.getConnection(source.url("")+"&connectTimeout=5000&socketTimeout=5000",source.user,source.password);anchor},
        ()=>DriverManager.getConnection(state.url(""),state.user,state.password),captureUser,"unused_"+suffix,sourceUuid,stateUuid)
      def connectionId(c:java.sql.Connection):Long=query(c,"SELECT CONNECTION_ID() AS id").elements.head.asJsObject.fields("id").convertTo[String].toLong
      try {
        observer.beforeStart("attempt",0L)
        active=DriverManager.getConnection(source.url("")+"&connectTimeout=5000&socketTimeout=5000",captureUser,secret)
        observer.sample("attempt",0L,includeOwner=true).status shouldBe "UNKNOWN"
        val ids=Vector(connectionId(anchor),connectionId(active))
        write(gate,"source-restart-request.json",JsObject("uuid"->JsString(sourceUuid),"port"->JsNumber(source.port),
          "sourceConnectionIds"->JsArray(ids.map(JsNumber(_)))))
        awaitCondition("leased source restart response",90.seconds)(Files.exists(gate.resolve("source-restart-response.json")))
        val response=Files.readString(gate.resolve("source-restart-response.json")).parseJson.asJsObject
        response.fields.get("restarted") shouldBe Some(JsBoolean(true))
        val oldAnchor=Try(observer.sample("attempt",0L,includeOwner=true))
        val oldCapture=Try(query(active,"SELECT 1 AS alive"))
        val newUuid=uuid(source)
        write(out,"result.json",JsObject("case"->JsString("B08-source-restart"),"restart"->response,
          "oldObserverFailed"->JsBoolean(oldAnchor.isFailure),"oldCaptureFailed"->JsBoolean(oldCapture.isFailure),
          "oldObserverErrorClass"->oldAnchor.failed.toOption.map(e=>JsString(e.getClass.getName)).getOrElse(JsNull),
          "actualSourceUuidAfter"->JsString(newUuid),"scope"->JsString("isolated source restart; observer component only; no Actor or Sink execution")))
        newUuid shouldBe sourceUuid;oldAnchor.isFailure shouldBe true;oldCapture.isFailure shouldBe true
      }finally {if(active!=null)Try(active.close());Try(observer.close())}
    }
    for (variant <- Vector("missing-process", "wrong-source-uuid")) {
      s"refuse observer startup for $variant" taggedAs ExternalIntegration in {
        if (!sys.env.get("RUN_CAPTURE_ADMISSION_MATRIX").contains("1")) cancel("exclusive dual-MySQL slot required")
        require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
        val source = Settings.environment(); val state = source.copy(port = sys.env("CAPTURE_STATE_PORT").toInt)
        require(source.port != state.port && !Set(3306, 13365, 13366).contains(source.port) &&
          !Set(3306, 13365, 13366).contains(state.port))
        def uuid(s: Settings): String = withConnection(s, "")(query(_, "SELECT @@server_uuid AS uuid"))
          .elements.head.asJsObject.fields("uuid").convertTo[String]
        val sourceUuid = uuid(source); val stateUuid = uuid(state)
        require(sourceUuid == sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateUuid == sys.env("CAPTURE_EXPECT_STATE_UUID"))
        val suffix = UUID.randomUUID().toString.replace("-", "").take(12)
        val user = "obs_" + suffix; val secret = UUID.randomUUID().toString
        val out = Path.of(sys.env("CAPTURE_ADMISSION_EVIDENCE")).resolve("observer_" + variant + "_" + suffix)
        Files.createDirectories(out)
        withConnection(source, "")(executeSql(_, s"CREATE USER '$user'@'127.0.0.1' IDENTIFIED BY '$secret'"))
        val open = if (variant == "missing-process")
          () => DriverManager.getConnection(source.url(""), user, secret)
        else () => DriverManager.getConnection(source.url(""), source.user, source.password)
        val expected = if (variant == "wrong-source-uuid") UUID.randomUUID().toString else sourceUuid
        val observer = CaptureResourceObserver.exclusiveAccounts(open,
          () => DriverManager.getConnection(state.url(""), state.user, state.password),
          "unused_source_" + suffix, "unused_state_" + suffix, expected, stateUuid)
        try {
          val attempt = Try(observer.beforeStart("attempt", 0L))
          write(out, "result.json", JsObject("case" -> JsString("B08-" + variant),
            "scope" -> JsString("real MySQL observer startup; no Reader, Actor or Sink execution"),
            "actualSourceUuid" -> JsString(sourceUuid), "expectedSourceUuid" -> JsString(expected),
            "stateUuid" -> JsString(stateUuid), "startupRefused" -> JsBoolean(attempt.isFailure),
            "errorClass" -> attempt.failed.toOption.map(e => JsString(e.getClass.getName)).getOrElse(JsNull)))
          attempt.isFailure shouldBe true
        } finally Try(observer.close())
      }
    }

    "not confirm release when PROCESS visibility is revoked after anchoring" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_CAPTURE_ADMISSION_MATRIX").contains("1")) cancel("exclusive dual-MySQL slot required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val source = Settings.environment()
      val state = source.copy(port = sys.env("CAPTURE_STATE_PORT").toInt)
      require(source.port != state.port && !Set(3306, 13365, 13366).contains(source.port) &&
        !Set(3306, 13365, 13366).contains(state.port))
      def uuid(s: Settings): String = withConnection(s, "")(query(_, "SELECT @@server_uuid AS uuid"))
        .elements.head.asJsObject.fields("uuid").convertTo[String]
      val sourceUuid = uuid(source); val stateUuid = uuid(state)
      require(sourceUuid == sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateUuid == sys.env("CAPTURE_EXPECT_STATE_UUID"))
      val suffix = UUID.randomUUID().toString.replace("-", "").take(12)
      val observerUser = "obs_" + suffix; val captureUser = "cap_" + suffix
      val secret = UUID.randomUUID().toString
      val out = Path.of(sys.env("CAPTURE_ADMISSION_EVIDENCE")).resolve("observer_" + suffix)
      Files.createDirectories(out)
      def admin(q: String): Unit = withConnection(source, "")(executeSql(_, q))
      admin(s"CREATE USER '$observerUser'@'127.0.0.1' IDENTIFIED BY '$secret'")
      admin(s"CREATE USER '$captureUser'@'127.0.0.1' IDENTIFIED BY '$secret'")
      admin(s"GRANT PROCESS ON *.* TO '$observerUser'@'127.0.0.1'")
      val observer = CaptureResourceObserver.exclusiveAccounts(
        () => DriverManager.getConnection(source.url(""), observerUser, secret),
        () => DriverManager.getConnection(state.url(""), state.user, state.password),
        captureUser, "unused_" + suffix, sourceUuid, stateUuid)
      var active: java.sql.Connection = null
      try {
        observer.beforeStart("attempt", 0L)
        active = DriverManager.getConnection(source.url(""), captureUser, secret)
        val connectionId = query(active, "SELECT CONNECTION_ID() AS id").elements.head.asJsObject.fields("id")
        val before = observer.sample("attempt", 0L, includeOwner = true)
        before.status shouldBe "UNKNOWN"
        admin(s"REVOKE PROCESS ON *.* FROM '$observerUser'@'127.0.0.1'")
        val after = Try(observer.sample("attempt", 0L, includeOwner = true))
        val status = after.map(_.status).getOrElse("OBSERVATION_ERROR")
        write(out, "result.json", JsObject("case" -> JsString("B08-process-revoked"),
          "scope" -> JsString("real MySQL observer component; no Actor or Sink execution"),
          "sourceUuid" -> JsString(sourceUuid), "stateUuid" -> JsString(stateUuid),
          "captureConnectionId" -> connectionId, "before" -> JsString(before.status),
          "after" -> JsString(status),
          "errorClass" -> after.failed.toOption.map(e => JsString(e.getClass.getName)).getOrElse(JsNull)))
        withClue("a live capture session must not become invisible and reusable after permission revocation") {
          status should not be "CONFIRMED"
        }
      } finally {
        if (active != null) active.close()
        observer.close()
      }
    }

    "fail observation when its original MySQL connection is disconnected" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_CAPTURE_ADMISSION_MATRIX").contains("1")) cancel("exclusive dual-MySQL slot required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val source = Settings.environment(); val state = source.copy(port = sys.env("CAPTURE_STATE_PORT").toInt)
      require(source.port != state.port && !Set(3306, 13365, 13366).contains(source.port) &&
        !Set(3306, 13365, 13366).contains(state.port))
      def uuid(s: Settings): String = withConnection(s, "")(query(_, "SELECT @@server_uuid AS uuid"))
        .elements.head.asJsObject.fields("uuid").convertTo[String]
      val sourceUuid = uuid(source); val stateUuid = uuid(state)
      require(sourceUuid == sys.env("CAPTURE_EXPECT_SOURCE_UUID") && stateUuid == sys.env("CAPTURE_EXPECT_STATE_UUID"))
      val suffix = UUID.randomUUID().toString.replace("-", "").take(12)
      val captureUser = "cap_" + suffix; val secret = UUID.randomUUID().toString
      val out = Path.of(sys.env("CAPTURE_ADMISSION_EVIDENCE")).resolve("observer_disconnect_" + suffix)
      Files.createDirectories(out)
      withConnection(source, "")(executeSql(_, s"CREATE USER '$captureUser'@'127.0.0.1' IDENTIFIED BY '$secret'"))
      val proxy = new MySQLCommitResponseProxy(source.host, source.port, (_, _) => ())
      val observer = CaptureResourceObserver.exclusiveAccounts(
        () => DriverManager.getConnection(source.copy(port = proxy.port).url("") + "&connectTimeout=5000&socketTimeout=5000", source.user, source.password),
        () => DriverManager.getConnection(state.url(""), state.user, state.password),
        captureUser, "unused_" + suffix, sourceUuid, stateUuid)
      var active: java.sql.Connection = null
      try {
        observer.beforeStart("attempt", 0L)
        active = DriverManager.getConnection(source.url(""), captureUser, secret)
        val connectionId = query(active, "SELECT CONNECTION_ID() AS id").elements.head.asJsObject.fields("id")
        observer.sample("attempt", 0L, includeOwner = true).status shouldBe "UNKNOWN"
        proxy.close()
        val after = Try(observer.sample("attempt", 0L, includeOwner = true))
        val live = query(active, "SELECT 1 AS alive").elements.nonEmpty
        write(out, "result.json", JsObject("case" -> JsString("B08-observer-network-disconnect"),
          "scope" -> JsString("test loopback proxy disconnects only observer; real MySQL session remains; no Actor or Sink execution"),
          "sourceUuid" -> JsString(sourceUuid), "stateUuid" -> JsString(stateUuid), "captureConnectionId" -> connectionId,
          "captureStillConnected" -> JsBoolean(live), "observationFailed" -> JsBoolean(after.isFailure),
          "errorClass" -> after.failed.toOption.map(e => JsString(e.getClass.getName)).getOrElse(JsNull)))
        live shouldBe true
        after.isFailure shouldBe true
      } finally {
        if (active != null) active.close()
        Try(observer.close())
        proxy.close()
      }
    }
  }
}
