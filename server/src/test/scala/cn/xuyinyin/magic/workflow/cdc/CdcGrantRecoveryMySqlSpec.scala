package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig,MySQLCdcSourceNode,MySQLCdcStateConfig}
import cn.xuyinyin.magic.connectors.mysql.source.capture.{CapturePoint,MySQLCaptureStore}
import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import cn.xuyinyin.magic.tags.ExternalIntegration
import cn.xuyinyin.magic.workflow.engine.CdcExecutionException
import cn.xuyinyin.magic.workflow.bootstrap.BootstrapProgress
import cn.xuyinyin.magic.workflow.scheduling._
import com.zaxxer.hikari.{HikariConfig,HikariDataSource}
import java.lang.reflect.{InvocationTargetException,Method,Proxy}
import java.nio.file.{Files,Path}
import java.security.KeyPairGenerator
import java.sql.{Connection,DriverManager,SQLException}
import java.util.{Base64,UUID}
import java.util.concurrent.atomic.AtomicBoolean
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

/** Atomicity with real MySQL; old-worker and guardian data are synthetic and do not prove isolation. */
class CdcGrantRecoveryMySqlSpec extends AnyWordSpec with Matchers {
  "B recovery grants" should {
    "atomically retain evidence, survive a lost commit reply and bind the permit to its successor" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CDC_GRANT_PROBE").contains("1"))cancel("isolated dual MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val sourcePort=sys.env("CDC_GRANT_SOURCE_PORT").toInt;val statePort=sys.env("CDC_GRANT_STATE_PORT").toInt
      require(sourcePort!=statePort && !Set(3306,13365,13366).contains(sourcePort) && !Set(3306,13365,13366).contains(statePort))
      val user=sys.env("CDC_GRANT_USER");val password=sys.env("CDC_GRANT_PASSWORD")
      val sourceUuid=sys.env("CAPTURE_EXPECT_SOURCE_UUID");val stateUuid=sys.env("CAPTURE_EXPECT_STATE_UUID")
      val suffix=UUID.randomUUID().toString.replace("-","").take(12);val schema="pekko_test_cdc_window_"+suffix
      val sourceUser="gs_"+suffix;val sdkUser="gd_"+suffix;val capture="grant_"+suffix
      def url(port:Int,name:String)=s"jdbc:mysql://127.0.0.1:$port/$name?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=5000"
      def sql(c:Connection,query:String):Unit={val q=c.createStatement();try q.execute(query)finally q.close()}
      def identity(c:Connection):String={val q=c.createStatement();try {val r=q.executeQuery("SELECT @@server_uuid");try {r.next();r.getString(1)}finally r.close()}finally q.close()}
      def admin(port:Int,expected:String,newUser:String):Unit={
        val c=DriverManager.getConnection(url(port,""),user,password)
        try {
          require(identity(c)==expected,"isolated database lineage changed")
          sql(c,s"CREATE DATABASE `$schema`")
          val escaped=password.replace("\\","\\\\").replace("'","\\'")
          sql(c,s"CREATE USER '$newUser'@'%' IDENTIFIED BY '$escaped'")
          sql(c,s"GRANT ALL ON `$schema`.* TO '$newUser'@'%'")
        }finally c.close()
      }
      admin(sourcePort,sourceUuid,sourceUser);admin(statePort,stateUuid,sdkUser)
      val stateUrl=url(statePort,schema);val sourceUrl=url(sourcePort,schema)
      val sourceAdmin=DriverManager.getConnection(sourceUrl,user,password)
      try sql(sourceAdmin,"CREATE TABLE pekko_capture_control(operation_id VARCHAR(128) PRIMARY KEY,action VARCHAR(32) NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL) ENGINE=InnoDB")finally sourceAdmin.close()
      val hc=new HikariConfig();hc.setJdbcUrl(stateUrl);hc.setUsername(user);hc.setPassword(password);hc.setMaximumPoolSize(4)
      val ds=new HikariDataSource(hc)
      val out=Path.of(sys.env("CDC_GRANT_EVIDENCE")).resolve(suffix);Files.createDirectories(out)
      try {
        val schemaConn=ds.getConnection
        try Vector("db/mysql/pekko-table-scheduling-schema.sql","db/mysql/pekko-snapshot-ownership-schema.sql").foreach { name=>
          val in=getClass.getClassLoader.getResourceAsStream(name);val ddl=try scala.io.Source.fromInputStream(in).mkString finally in.close()
          ddl.split(";").map(_.trim).filter(_.nonEmpty).foreach(sql(schemaConn,_))
        }finally schemaConn.close()
        val scheduling=new TableSchedulingStore(ds,SchedulingLimits(2,1,8,1,1048576,8),ownershipEnabled=true)
        val self=SnapshotOwnerIdentity("candidate-"+suffix,"test",ProcessHandle.current().pid(),ProcessHandle.current().info().startInstant().orElseThrow().toString)
        scheduling.ownership.get.registerOwner(self)
        val sourceEndpoint=SnapshotOwnerJdbc.endpoint(sourceUrl);val stateEndpoint=SnapshotOwnerJdbc.endpoint(stateUrl)
        SnapshotOwnerJdbc.install(SnapshotOwnerJdbc.Context(self,scheduling,Map(sourceEndpoint->sourceUuid,stateEndpoint->stateUuid),()=>()))
        val cfgSource=MySQLCdcSourceConfig(capture,"127.0.0.1",sourcePort,schema,"pekko_capture_control",sourceUser,password,810,128,100)
        val control=MySQLCdcStateConfig(stateUrl,user,password,"grant_offsets","grant_history",0)
        val sdk=control.copy(username=sdkUser)
        val captureCfg=CdcCaptureSettings(capture,cfgSource,sdk,control,sourceUuid,stateUuid)
        val log=new MySQLCaptureStore(()=>DriverManager.getConnection(stateUrl,sdkUser,password),capture,sourceUuid,1024*1024)
        log.initialize();val recorded=log.rememberSchema("rows","{}")
        val task=CdcTaskRequest("request",capture,"rows","g1","target_rows")
        val offset="{\"file\":\"mysql-bin.000001\",\"pos\":110}"
        log.cover(offset);log.registerAnchored(task.taskId,task.table,task.generation,recorded.id,offset)
        val offsetTable=MySQLCdcSourceNode.connectorStateTables(sdk,capture)._1
        val d=DriverManager.getConnection(stateUrl,sdkUser,password)
        try {
          sql(d,s"CREATE TABLE `$offsetTable` (id INT PRIMARY KEY,offset_key TEXT,offset_val TEXT) ENGINE=InnoDB")
          val q=d.prepareStatement(s"INSERT INTO `$offsetTable` VALUES(1,?,?)")
          try {q.setString(1,JsArray(JsString(capture),JsObject("server"->JsString(capture))).compactPrint);q.setString(2,offset);q.executeUpdate()}finally q.close()
        }finally d.close()
        val candidate=CdcManagedPendingB.verify(captureCfg,task.taskId);candidate.markerPresent shouldBe false
        log.recoverablePendingProofAt(offset,task.taskId).map(_.mode) shouldBe Some("PENDING")
        val pendingSource=DriverManager.getConnection(sourceUrl,sourceUser,password)
        try {
          val stopId=log.markerId(task.taskId,stop=true)
          val insert=pendingSource.prepareStatement("INSERT INTO pekko_capture_control VALUES(?,?,?,?)")
          try {insert.setString(1,stopId);insert.setString(2,"STOP");insert.setString(3,task.table);insert.setString(4,task.generation);insert.executeUpdate()}finally insert.close()
          intercept[IllegalArgumentException](CdcManagedPendingB.verify(captureCfg,task.taskId))
          // Remove only this synthetic fixture row so the same isolated sample can exercise later cases.
          val delete=pendingSource.prepareStatement("DELETE FROM pekko_capture_control WHERE operation_id=?")
          try {delete.setString(1,stopId);delete.executeUpdate() shouldBe 1}finally delete.close()
          CdcManagedPendingB.verify(captureCfg,task.taskId).markerPresent shouldBe false
        }finally pendingSource.close()
        val keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val public=Base64.getEncoder.encodeToString(keys.getPublic.getEncoded)
        val guardian=GuardianBinding("test","host","guardian",public)
        val manifest=JsObject("version"->JsNumber(1),"accounts"->JsArray(Vector(
          JsObject("endpoint"->JsString(sourceEndpoint),"serverUuid"->JsString(sourceUuid),"user"->JsString(sourceUser)),
          JsObject("endpoint"->JsString(stateEndpoint),"serverUuid"->JsString(stateUuid),"user"->JsString(sdkUser))))).compactPrint
        val inventory=Map(sourceEndpoint->(sourceUuid,Set(sourceUser)),stateEndpoint->(stateUuid,Set(sdkUser)))
        def proof(boot:String,pid:Long):CdcManagedIsolation.Verified={
          import SnapshotGuardianWire._
          val id=SnapshotOwnerIdentity(boot,"test",pid,"test-start")
          val m=CdcManagedOwner(boot,"test","host","guardian",public,pid,"test-start","profile")
          val receipt=SnapshotIsolationReceipt("operation-"+boot,id,"managed-child-jvm",1,"{\"alive\":false,\"automaticRestart\":false}")
          val signed=sign(JsObject("guardian"->guardian.toJson,"receipt"->receipt.toJson),keys.getPrivate).compactPrint
          CdcManagedIsolation.verify(m,manifest,guardian,SnapshotWorkerLaunch(boot,"profile","retired",Some(id),Some(receipt.operationId),Some(signed)),
            SnapshotOwner(id,"db_drained",Some(receipt.operationId),1,Some(1),None),inventory)
        }
        val firstProof=proof("old-boot",101);val wrongProof=proof("wrong-boot",102)
        val dropReply=new AtomicBoolean(false)
        def openControl():Connection={
          val raw=DriverManager.getConnection(stateUrl,user,password)
          Proxy.newProxyInstance(classOf[Connection].getClassLoader,Array(classOf[Connection]),(_,method:Method,args:Array[AnyRef])=>{
            try {
              val answer=method.invoke(raw,Option(args).getOrElse(Array.empty[AnyRef]):_*)
              if(method.getName=="commit" && dropReply.compareAndSet(true,false))throw new SQLException("injected lost reply after actual MySQL COMMIT")
              answer
            }catch {case e:InvocationTargetException=>throw e.getCause}
          }).asInstanceOf[Connection]
        }
        val store=new CdcTaskStore(()=>openControl(),CdcPlatformLimits(snapshotSteps=2,tailSteps=1));store.initialize()
        // Real COMMIT succeeds and the response is lost: read back this exact
        // attempt and full managed binding before treating beginOwner as done.
        dropReply.set(true)
        store.beginOwner("attempt-old",1L,Some(firstProof.managed)) shouldBe true
        store.ownerAttempt shouldBe Some("attempt-old"->"ACTIVE")
        store.managedOwner("attempt-old") shouldBe Some(firstProof.managed)
        intercept[IllegalArgumentException](store.beginOwner("attempt-old",2L,Some(wrongProof.managed)))
        store.recordManagedManifest("attempt-old",manifest,2L)
        store.submit(task,3L)
        val other=CdcTaskRequest("other",capture,"other_rows","g1","other_target")
        store.submit(other,3L)
        val grant=store.claim("attempt-old","SNAPSHOT",Set(capture),4L,Some(Set(task.taskId))).get
        val otherGrant=store.claim("attempt-old","SNAPSHOT",Set(capture),4L,Some(Set(other.taskId))).get
        // A second snapshot can have an invisible in-flight B and must block.
        intercept[IllegalArgumentException](store.releaseForPendingB("attempt-old",firstProof,candidate,4L))
        // Synthetic task-index activation only; no actual Journal or target proof.
        store.finish(otherGrant,BootstrapProgress(phase="STREAMING",executionId="other-execution"),Some("synthetic-activation"),"ADMISSION_READY",true,false,0,4L)
        val otherTail=store.claim("attempt-old","TAIL",Set(capture),4L,Some(Set(other.taskId))).get
        store.finishUnknown(grant,"local_release_unconfirmed",5L)
        intercept[IllegalArgumentException](store.releaseForPendingB("attempt-old",wrongProof,candidate,6L))
        store.ownerStatus shouldBe "UNKNOWN";store.grants.map(_._3).toSet shouldBe Set("UNKNOWN","ACTIVE")
        store.finishUnknown(otherTail,"local_release_unconfirmed",5L)
        store.soleOutstandingTask("attempt-old") shouldBe None
        intercept[IllegalArgumentException](store.releaseForPendingB("attempt-old",firstProof,candidate,6L))
        store.grants.count(_._3=="UNKNOWN") shouldBe 2
        // Restore only the synthetic secondary fixture so the same sample can
        // independently verify the one-UNKNOWN case below.
        val restore=DriverManager.getConnection(stateUrl,user,password)
        try {
          val q=restore.prepareStatement("UPDATE pekko_cdc_grants_v1 SET status='ACTIVE' WHERE grant_id=?")
          try {q.setString(1,otherTail.id);q.executeUpdate() shouldBe 1}finally q.close()
          val q2=restore.prepareStatement("UPDATE pekko_cdc_tasks_v1 SET phase='STREAMING',release_status='IN_USE',runtime_health='WORKING',failure_code=NULL WHERE task_id=?")
          try {q2.setString(1,other.taskId);q2.executeUpdate() shouldBe 1}finally q2.close()
        }finally restore.close()
        store.soleOutstandingTask("attempt-old").map(_.taskId) shouldBe Some(task.taskId)
        dropReply.set(true)
        val lost=intercept[CdcExecutionException](store.releaseForPendingB("attempt-old",firstProof,candidate,7L))
        store.ownerStatus shouldBe "STOPPED";store.grants shouldBe empty
        val otherAfter=store.get(other.taskId).get
        otherAfter.phase shouldBe "STREAMING"
        otherAfter.releaseStatus shouldBe "RELEASED"
        otherAfter.runtimeHealth shouldBe "WAITING"
        val first=store.permitForOldOwner("attempt-old").get
        store.releaseForPendingB("attempt-old",firstProof,candidate,8L).id shouldBe first.id
        val secondProof=proof("new-boot",103)
        store.beginOwner("attempt-new",9L,Some(secondProof.managed)) shouldBe true
        store.recordManagedManifest("attempt-new",manifest,10L)
        store.authorizedBPermits(capture,offset,Vector(task.taskId)) shouldBe true
        store.authorizedBPermits(capture,"{\"file\":\"mysql-bin.000001\",\"pos\":120}",Vector(task.taskId)) shouldBe false
        store.endOwner("attempt-new",confirmed=false,11L) shouldBe false
        store.soleOutstandingTask("attempt-new").map(_.taskId) shouldBe Some(task.taskId)
        // Two distinct attempts can persist permits in the same millisecond. Selection
        // must use the current owner and consumption state, not timestamp ordering.
        val second=store.releaseForPendingB("attempt-new",secondProof,candidate,7L)
        second.id should not be first.id
        store.permitForOldOwner("attempt-old").get.consumed shouldBe true
        val finalProof=proof("final-boot",104)
        store.beginOwner("attempt-final",13L,Some(finalProof.managed)) shouldBe true
        store.recordManagedManifest("attempt-final",manifest,13L)
        store.pendingBPermit(capture,task.taskId).map(_.id) shouldBe Some(second.id)
        store.authorizedBPermits(capture,offset,Vector(task.taskId)) shouldBe true
        // A clean local shutdown before the claimed permit is consumed must not
        // orphan that permit in a STOPPED owner that future recovery skips.
        store.endOwner("attempt-final",confirmed=true,14L) shouldBe false
        store.ownerStatus shouldBe "UNKNOWN"
        store.soleOutstandingTask("attempt-final").map(_.taskId) shouldBe Some(task.taskId)
        val third=store.releaseForPendingB("attempt-final",finalProof,candidate,7L)
        store.permitForOldOwner("attempt-new").get.consumed shouldBe true
        val successor=proof("successor-boot",105)
        store.beginOwner("attempt-successor",15L,Some(successor.managed)) shouldBe true
        store.recordManagedManifest("attempt-successor",manifest,15L)
        store.pendingBPermit(capture,task.taskId).map(_.id) shouldBe Some(third.id)
        val finalGrant=store.claim("attempt-successor","SNAPSHOT",Set(capture),16L,Some(Set(task.taskId))).get
        store.authorizedBPermit(first.id,task.taskId) shouldBe false
        store.authorizedBPermit(second.id,task.taskId) shouldBe false
        store.authorizedBPermit(third.id,task.taskId) shouldBe true
        def currentBudget():Int={val connection=DriverManager.getConnection(stateUrl,user,password)
          try {val q=connection.prepareStatement("SELECT attempts FROM pekko_cdc_b_budget_v1 WHERE task_id=?")
            try {q.setString(1,task.taskId);val r=q.executeQuery();try {r.next();r.getInt(1)}finally r.close()}finally q.close()}finally connection.close()}
        currentBudget() shouldBe 3
        // A real MySQL COMMIT succeeds, but the test JDBC wrapper loses its reply.
        // Exact rejection readback must prove that this permit was invalidated.
        dropReply.set(true)
        val rejectionLost=intercept[CdcExecutionException](store.rejectBPermit(third.id,task.taskId))
        rejectionLost.failure.code shouldBe "commit_outcome_unknown"
        store.rejectedBPermit(third.id,task.taskId) shouldBe true
        store.rejectedBPermit(third.id,"0"*64) shouldBe false
        store.authorizedBPermit(third.id,task.taskId) shouldBe false
        currentBudget() shouldBe 2
        store.rejectBPermit(third.id,task.taskId) shouldBe true
        currentBudget() shouldBe 2
        store.finishUnknown(finalGrant,"local_release_unconfirmed",17L)
        val fourth=store.releaseForPendingB("attempt-successor",successor,candidate,18L)
        currentBudget() shouldBe 3
        val finalProof2=proof("final-boot-2",106)
        store.beginOwner("attempt-final-2",19L,Some(finalProof2.managed)) shouldBe true
        store.recordManagedManifest("attempt-final-2",manifest,19L)
        val grant2=store.claim("attempt-final-2","SNAPSHOT",Set(capture),20L,Some(Set(task.taskId))).get
        store.authorizedBPermit(fourth.id,task.taskId) shouldBe true
        store.consumeBPermit(fourth.id,task.taskId) shouldBe true
        store.consumeBPermit(fourth.id,task.taskId) shouldBe true
        store.finishUnknown(grant2,"local_release_unconfirmed",21L)
        val exhausted=intercept[CdcPlatformException](store.releaseForPendingB("attempt-final-2",finalProof2,candidate,22L))
        exhausted.code shouldBe "AUTOMATIC_RECOVERY_EXHAUSTED"
        store.ownerStatus shouldBe "UNKNOWN";store.grants.map(_._3) shouldBe Vector("UNKNOWN")
        store.releaseForPendingB("attempt-final",finalProof,candidate,23L).id shouldBe third.id
        val budget=currentBudget()
        budget shouldBe 3
        val b=log.bind(task.taskId,CapturePoint("mysql-bin.000001",130,0),offset,stop=false)
        val bSequence=b.bSequence.get
        log.recoverableBoundAt(offset,task.taskId).map(_.mode) shouldBe Some("REPLAY")
        intercept[IllegalArgumentException](CdcManagedPendingB.verify(captureCfg,task.taskId))
        val advanced="{\"file\":\"mysql-bin.000001\",\"pos\":120}"
        val sourceControl=DriverManager.getConnection(sourceUrl,sourceUser,password)
        def marker(operation:String,action:String,table:String,generation:String):Unit={
          val q=sourceControl.prepareStatement("INSERT INTO pekko_capture_control VALUES(?,?,?,?)")
          try {q.setString(1,operation);q.setString(2,action);q.setString(3,table);q.setString(4,generation);q.executeUpdate();()}finally q.close()
        }
        try {
          marker(log.markerId(task.taskId,stop=false),"SUBSCRIBE",task.table,task.generation)
          CdcManagedPendingB.verify(captureCfg,task.taskId).markerPresent shouldBe true
          val replay=log.recoverableBoundAt(offset,task.taskId).get
          log.controlsBound(Vector((log.markerId(task.taskId,stop=true),"STOP",task.table,task.generation)),task.taskId,replay) shouldBe false
          val wake=MySQLCdcGroup.hash(capture+"|wake|old-boot")
          marker(wake,"WAKE","pekko_capture_control","old-boot")
          CdcManagedPendingB.verify(captureCfg,task.taskId).markerPresent shouldBe true
          val other=new cn.xuyinyin.magic.connectors.mysql.source.capture.MySQLCaptureStore(()=>DriverManager.getConnection(stateUrl,sdkUser,password),"other-capture",sourceUuid,128L*1024*1024)
          other.initialize()
          val w=cn.xuyinyin.magic.connectors.mysql.source.capture.CaptureWake
          val otherWake=w.operation("other-capture","old-boot",1)
          marker(otherWake,"WAKE",w.table("other-capture"),w.generation("old-boot",1))
          log.validWake(otherWake,w.table("other-capture"),w.generation("old-boot",1)) shouldBe true
          log.controlsBound(Vector((otherWake,"WAKE",w.table("other-capture"),w.generation("old-boot",1))),task.taskId,replay) shouldBe true
          CdcManagedPendingB.verify(captureCfg,task.taskId).markerPresent shouldBe true
          log.controlsBound(Vector((MySQLCdcGroup.hash(capture+"|wake|unknown-boot"),"WAKE","pekko_capture_control","unknown-boot")),task.taskId,replay) shouldBe false
          log.cover(advanced)
          val update=DriverManager.getConnection(stateUrl,sdkUser,password)
          try {val q=update.prepareStatement(s"UPDATE `$offsetTable` SET offset_val=? WHERE id=1");try {q.setString(1,advanced);q.executeUpdate()}finally q.close()}finally update.close()
          log.recoverableBoundAt(advanced,task.taskId) shouldBe None
          intercept[IllegalArgumentException](CdcManagedPendingB.verify(captureCfg,task.taskId))
          log.cover(advanced,Vector(bSequence))
          log.recoverableBoundAt(advanced,task.taskId).map(_.mode) shouldBe Some("COVERED")
          CdcManagedPendingB.verify(captureCfg,task.taskId).markerPresent shouldBe true
          val falseWake=MySQLCdcGroup.hash(capture+"|wake|unknown-boot")
          marker(falseWake,"WAKE","pekko_capture_control","unknown-boot")
          intercept[IllegalArgumentException](CdcManagedPendingB.verify(captureCfg,task.taskId))
          log.registerFence("pending-fence",task.taskId,"LOW","{}")
          log.recoverableBoundAt(advanced,task.taskId) shouldBe None
        } finally sourceControl.close()
        def evidenceRows(url:String,query:String):JsArray={
          val c=DriverManager.getConnection(url,user,password)
          try {val q=c.createStatement();try {val r=q.executeQuery(query);val rows=Vector.newBuilder[JsValue];val meta=r.getMetaData
            try while(r.next())rows+=JsObject((1 to meta.getColumnCount).map(i=>meta.getColumnLabel(i)->Option(r.getString(i)).map(JsString(_)).getOrElse(JsNull)).toMap)
            finally r.close();JsArray(rows.result())
          }finally q.close()}finally c.close()
        }
        val data=JsObject("scope"->JsString("real MySQL grant/permit transactions, synthetic D and synthetic signed guardian proof; no CDC Reader, Actor Journal or Sink"),
          "sourceUuid"->JsString(sourceUuid),"stateUuid"->JsString(stateUuid),"schema"->JsString(schema),"taskId"->JsString(task.taskId),
          "wrongProofRejected"->JsBoolean(true),"lostReplyException"->JsString(lost.failure.code),"oldPermit"->JsString(first.id),
          "newPermit"->JsString(second.id),"finalPermit"->JsString(third.id),"samePermitCreatedAt"->JsNumber(7),"currentPermitSelected"->JsBoolean(true),"restartBeforeGrant"->JsBoolean(true),
          "cleanStopWithPendingPermit"->JsString("UNKNOWN"),
          "oldPermitInvalidated"->JsBoolean(true),"permitConsumed"->JsBoolean(true),"budgetAttempts"->JsNumber(budget),
          "rejectionLostReply"->JsString(rejectionLost.failure.code),"rejectionExactReadback"->JsBoolean(true),"rejectionBudgetAfter"->JsNumber(2),"rejectionRetryIdempotent"->JsBoolean(true),
          "fourthAttempt"->JsString(exhausted.code),"finalGrant"->JsString("UNKNOWN"),"finalOwner"->JsString(store.ownerStatus),
          "boundBReplay"->JsString("REPLAY"),"advancedDWithoutReference"->JsString("REJECTED"),"boundBCovered"->JsString("COVERED"),
          "missingSourceMarker"->JsString("REJECTED"),"unknownStop"->JsString("REJECTED"),"knownWake"->JsString("ACCEPTED"),
          "pendingUnknownStop"->JsString("REJECTED"),"unknownWake"->JsString("REJECTED"),"unboundFence"->JsString("REJECTED"),"D"->advanced.parseJson,"barrierSequence"->JsNumber(bSequence),
          "sourceControls"->evidenceRows(sourceUrl,"SELECT operation_id,action,table_name,generation FROM pekko_capture_control ORDER BY operation_id"),
          "L"->log.evidence,"owner"->evidenceRows(stateUrl,"SELECT platform_id,owner_attempt,status FROM pekko_cdc_owner_v1"),
          "grants"->evidenceRows(stateUrl,"SELECT grant_id,task_id,owner_attempt,pool,status FROM pekko_cdc_grants_v1 ORDER BY created_at,grant_id"),
          "permits"->evidenceRows(stateUrl,"SELECT owner_attempt,permit_id,task_id,capture_id,offset_json,marker_present,table_name,generation,operation_id,claimed_by,consumed,created_at FROM pekko_cdc_b_recovery_v1 ORDER BY owner_attempt"),
          "budget"->evidenceRows(stateUrl,"SELECT task_id,attempts FROM pekko_cdc_b_budget_v1"),
          "rejections"->evidenceRows(stateUrl,"SELECT permit_id,task_id FROM pekko_cdc_b_rejection_v1"),
          "T"->JsString("N/A"),"J"->JsString("N/A"),"rawAckOrder"->JsString("N/A: synthetic ACK frames, no Reader callback"))
        Files.writeString(out.resolve("result.json"),data.prettyPrint)
      }catch {case e:Throwable=>
        Files.writeString(out.resolve("failure.json"),JsObject("class"->JsString(e.getClass.getName),"message"->JsString(Option(e.getMessage).getOrElse("").replace(password,"[REDACTED]"))).prettyPrint);throw e
      }finally ds.close()
    }
  }
}
