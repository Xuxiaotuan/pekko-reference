package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import cn.xuyinyin.magic.connectors.mysql.source.capture.CaptureFenceBinding
import cn.xuyinyin.magic.workflow.scheduling._
import java.lang.reflect.InvocationTargetException
import java.security.KeyPairGenerator
import java.sql.DriverManager
import java.sql.{Connection,SQLException}
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import cn.xuyinyin.magic.workflow.engine.{CdcExecutionException,ExecutionFailure}
import java.util.{Base64,UUID}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

/** Synthetic isolation and B inputs: exercises state-store atomicity, not source/guardian proof. */
class CdcRecoveryFenceStorageSpec extends AnyWordSpec with Matchers {
  "a versioned recovery permit" should {
    "keep an owner unknown when a consumed versioned claim remains during shutdown" in {
      for (downgraded <- Seq(false,true)) {
        val url="jdbc:h2:mem:cdc_end_owner_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        val store=new CdcTaskStore(()=>DriverManager.getConnection(url),CdcPlatformLimits());store.initialize()
        val managed=CdcManagedOwner("boot","deployment","host","guardian","key",123,"start","profile")
        store.beginOwner("old",1L,Some(managed)) shouldBe true
        val c=DriverManager.getConnection(url)
        try {val q=c.createStatement();try {
          q.execute(s"INSERT INTO pekko_cdc_b_recovery_v1(owner_attempt,permit_id,task_id,capture_id,offset_json,marker_present,table_name,generation,operation_id,signed_proof,created_at,claimed_by,consumed,proof_version) VALUES('first','permit','task','capture','{}',FALSE,'orders','g1','op','proof',1,'old',TRUE,${if(downgraded)1 else 2})")
          if(downgraded)q.execute("INSERT INTO pekko_cdc_b_fence_v2 VALUES('permit','uuid','db',1,'token',0,'op','SUBSCRIBE','orders','g1')")
        }finally q.close()}finally c.close()
        store.endOwner("old",confirmed=true,2L) shouldBe false
        store.ownerStatus shouldBe "UNKNOWN"
        store.beginOwner("replacement",3L,Some(managed.copy(bootId="next"))) shouldBe false
      }
    }
    "still allow a clean owner with only a consumed legacy claim to stop" in {
      val url="jdbc:h2:mem:cdc_end_legacy_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val store=new CdcTaskStore(()=>DriverManager.getConnection(url),CdcPlatformLimits());store.initialize()
      val managed=CdcManagedOwner("boot","deployment","host","guardian","key",123,"start","profile")
      store.beginOwner("old",1L,Some(managed)) shouldBe true
      val c=DriverManager.getConnection(url)
      try {val q=c.createStatement();try {
        q.execute("INSERT INTO pekko_cdc_b_recovery_v1(owner_attempt,permit_id,task_id,capture_id,offset_json,marker_present,table_name,generation,operation_id,signed_proof,created_at,claimed_by,consumed,proof_version) VALUES('first','permit','task','capture','{}',FALSE,'orders','g1','op','proof',1,'old',TRUE,1)")
      }finally q.close()}finally c.close()
      store.endOwner("old",confirmed=true,2L) shouldBe true
      store.ownerStatus shouldBe "STOPPED"
    }
    "not admit a replacement for a consumed versioned permit without a grant" in {
      val url="jdbc:h2:mem:cdc_consumed_owner_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val store=new CdcTaskStore(()=>DriverManager.getConnection(url),CdcPlatformLimits());store.initialize()
      val c=DriverManager.getConnection(url)
      try {val q=c.createStatement();try {
        q.execute("INSERT INTO pekko_cdc_b_recovery_v1(owner_attempt,permit_id,task_id,capture_id,offset_json,marker_present,table_name,generation,operation_id,signed_proof,created_at,claimed_by,consumed,proof_version) VALUES('first','permit','task','capture','{}',FALSE,'orders','g1','op','proof',1,'old',TRUE,2)")
      }finally q.close()}finally c.close()
      val replacement=CdcManagedOwner("boot","deployment","host","guardian","key",123,"start","profile")
      store.ownerStatus shouldBe "STOPPED"
      store.grants shouldBe empty
      store.hasClaimedVersionedFence("old") shouldBe true
      store.hasClaimedVersionedFence("replacement") shouldBe false
      store.beginOwner("replacement",2L,Some(replacement)) shouldBe false
      store.ownerStatus shouldBe "STOPPED"
      val corrupt=DriverManager.getConnection(url)
      try {val q=corrupt.createStatement();try {
        q.executeUpdate("UPDATE pekko_cdc_b_recovery_v1 SET proof_version=1 WHERE permit_id='permit'") shouldBe 1
        q.executeUpdate("INSERT INTO pekko_cdc_b_fence_v2 VALUES('permit','uuid','db',1,'token',0,'op','SUBSCRIBE','orders','g1')") shouldBe 1
      }finally q.close()}finally corrupt.close()
      store.hasClaimedVersionedFence("old") shouldBe true
      store.beginOwner("replacement",3L,Some(replacement)) shouldBe false
    }
    "refuse to recreate an owner when only a Reader startup token remains" in {
      val url="jdbc:h2:mem:cdc_reader_guard_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val store=new CdcTaskStore(()=>DriverManager.getConnection(url),CdcPlatformLimits());store.initialize()
      val c=DriverManager.getConnection(url)
      try {val q=c.createStatement();try {
        q.execute("DELETE FROM pekko_cdc_owner_v1")
        q.execute("INSERT INTO pekko_cdc_reader_start_v2 VALUES('permit','task','capture','old','hash',7,'uuid','db','offset',3,2)")
      }finally q.close()}finally c.close()
      intercept[CdcPlatformException](store.initialize()).code shouldBe "CONTROL_OWNER_MISSING"
    }
    "upgrade an existing v1 permit table without changing its data" in {
      val url="jdbc:h2:mem:cdc_old_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val c=DriverManager.getConnection(url)
      try {
        val s=c.createStatement()
        try {
          s.execute("CREATE TABLE pekko_cdc_b_recovery_v1(owner_attempt VARCHAR(128) PRIMARY KEY,permit_id VARCHAR(128) NOT NULL,task_id CHAR(64) NOT NULL,capture_id VARCHAR(64) NOT NULL,offset_json LONGTEXT NOT NULL,marker_present BOOLEAN NOT NULL,table_name VARCHAR(128) NOT NULL,generation VARCHAR(128) NOT NULL,operation_id VARCHAR(128) NOT NULL,signed_proof LONGTEXT NOT NULL,created_at BIGINT NOT NULL,claimed_by VARCHAR(128),consumed BOOLEAN NOT NULL DEFAULT FALSE)")
          s.execute("INSERT INTO pekko_cdc_b_recovery_v1(owner_attempt,permit_id,task_id,capture_id,offset_json,marker_present,table_name,generation,operation_id,signed_proof,created_at) VALUES('old','legacy','task','capture','{}',FALSE,'orders','g1','op','proof',1)")
          s.execute("CREATE TABLE pekko_cdc_owner_v1(platform_id VARCHAR(32) PRIMARY KEY,owner_attempt VARCHAR(128),status VARCHAR(32) NOT NULL,updated_at BIGINT NOT NULL)")
          s.execute("INSERT INTO pekko_cdc_owner_v1 VALUES('default',NULL,'STOPPED',0)")
        } finally s.close()
      } finally c.close()
      val store=new CdcTaskStore(()=>DriverManager.getConnection(url),CdcPlatformLimits());store.initialize();store.initialize()
      store.permitForOldOwner("old").map(_.id) shouldBe Some("legacy")
      val verify=DriverManager.getConnection(url)
      try {val s=verify.createStatement();try {val r=s.executeQuery("SELECT proof_version FROM pekko_cdc_b_recovery_v1 WHERE permit_id='legacy'");try {r.next() shouldBe true;r.getInt(1) shouldBe 1}finally r.close()}finally s.close()}finally verify.close()
    }
    "persist an immutable source binding atomically with grant release" in {
      val url="jdbc:h2:mem:cdc_fence_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val loseCommitReply=new AtomicBoolean(false)
      def open():Connection={
        val raw=DriverManager.getConnection(url)
        Proxy.newProxyInstance(getClass.getClassLoader,Array(classOf[Connection]),(_,method,args)=>{
          val actual=Option(args).getOrElse(Array.empty[AnyRef])
          try {
            val result=method.invoke(raw,actual:_*)
            if(method.getName=="commit" && loseCommitReply.compareAndSet(true,false))throw new SQLException("injected reply loss","08006")
            result
          }catch {case e:InvocationTargetException=>throw e.getCause}
        }).asInstanceOf[Connection]
      }
      val store=new CdcTaskStore(()=>open(),CdcPlatformLimits());store.initialize()
      val keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();val public=Base64.getEncoder.encodeToString(keys.getPublic.getEncoded)
      val guardian=GuardianBinding("deployment","host","guardian",public);val identity=SnapshotOwnerIdentity("boot","deployment",123,"start")
      val owner=CdcManagedOwner("boot","deployment","host","guardian",public,123,"start","profile")
      val receipt=SnapshotIsolationReceipt("isolation-op",identity,"managed-child-jvm",1,"{\"alive\":false,\"automaticRestart\":false}")
      import SnapshotGuardianWire._
      val signed=sign(JsObject("guardian"->guardian.toJson,"receipt"->receipt.toJson),keys.getPrivate).compactPrint
      val manifest="{\"version\":1,\"accounts\":[{\"endpoint\":\"source:3306\",\"serverUuid\":\"uuid\",\"user\":\"sdk\"}]}"
      val proof=CdcManagedIsolation.verify(owner,manifest,guardian,SnapshotWorkerLaunch("boot","profile","retired",Some(identity),Some("isolation-op"),Some(signed)),
        SnapshotOwner(identity,"db_drained",Some("isolation-op"),1,Some(1),None),Map("source:3306"->("uuid",Set("sdk"))))
      store.beginOwner("old",1L,Some(owner)) shouldBe true;store.recordManagedManifest("old",manifest,2L)
      val request=CdcTaskRequest("request","capture","orders","g1","target_orders");store.submit(request,3L)
      val grant=store.claim("old","SNAPSHOT",Set("capture"),4L).get;store.finishUnknown(grant,"local_release_unconfirmed",5L)
      val uuid="d12dd737-304f-4547-992b-a65d11ce0e75"
      val binding=CaptureFenceBinding(uuid,"source_db",7L,"a"*64,13L,MySQLCdcGroup.hash("capture|B|"+request.taskId),"SUBSCRIBE","orders","g1")
      val constructor=classOf[CdcManagedPendingB.Verified].getConstructors.head
      val b=constructor.newInstance("capture",request.taskId,"{\"file\":\"binlog.000001\",\"pos\":1}",Boolean.box(false),"orders","g1",uuid,"source_db",Some(binding)).asInstanceOf[CdcManagedPendingB.Verified]
      val intentApi=store.getClass.getDeclaredMethods.find(m=>m.getName=="recordFenceIntent" && m.getParameterCount==4)
      withClue("source fence intent must exist before source commit"){intentApi should not be empty}
      intentApi.get.setAccessible(true)
      def record(f:CaptureFenceBinding):CaptureFenceBinding=try intentApi.get.invoke(store,"old",proof,b,f).asInstanceOf[CaptureFenceBinding]
        catch {case e:InvocationTargetException=>throw e.getCause}
      record(binding) shouldBe binding
      record(binding) shouldBe binding
      intercept[IllegalArgumentException](record(binding.copy(ownerToken="b"*64)))
      store.withFenceState("old",proof,b,binding) { c=>
        val q=c.prepareStatement("SELECT COUNT(*) FROM pekko_cdc_b_fence_intent_v2 WHERE owner_attempt=?")
        try {q.setString(1,"old");val r=q.executeQuery();try {r.next();r.getInt(1)}finally r.close()}finally q.close()
      } shouldBe 1
      intercept[IllegalArgumentException](store.withFenceState("old",proof,b,binding.copy(epoch=8L))(_=>()))
      val scanApi=store.getClass.getDeclaredMethods.find(m=>m.getName=="advanceFenceScan" && m.getParameterCount==7)
      withClue("bounded source scan needs durable compare-and-set progress"){scanApi should not be empty}
      scanApi.get.setAccessible(true)
      def advance(from:String,to:String,done:Boolean):AnyRef=try scanApi.get.invoke(store,"old",proof,b,binding,from,to,Boolean.box(done))
        catch {case e:InvocationTargetException=>throw e.getCause}
      val first=advance("","1"*64,false)
      advance("","1"*64,false) shouldBe first
      store.scanProgress("old").map(_.binding) shouldBe Some(binding)
      store.scanProgress("old").map(_.cursor) shouldBe Some("1"*64)
      intercept[IllegalArgumentException](advance("","2"*64,false))
      advance("1"*64,"1"*64,true)
      intercept[IllegalArgumentException](advance("1"*64,"2"*64,false))
      val method=store.getClass.getDeclaredMethods.find(m=>m.getName=="releaseForPendingB" && m.getParameterCount==5 && m.getParameterTypes.last==classOf[CaptureFenceBinding])
      withClue("versioned release API"){method should not be empty}
      method.get.setAccessible(true)
      def release(f:CaptureFenceBinding):CdcBRecoveryPermit=try method.get.invoke(store,"old",proof,b,Long.box(6),f).asInstanceOf[CdcBRecoveryPermit]
        catch {case e:InvocationTargetException=>throw e.getCause}
      intercept[IllegalArgumentException](store.releaseForPendingB("old",proof,b,6L))
      intercept[IllegalArgumentException](release(binding.copy(epoch=8L)))
      store.ownerStatus shouldBe "UNKNOWN";store.grants.map(_._3) shouldBe Vector("UNKNOWN")
      val permit=release(binding);store.ownerStatus shouldBe "STOPPED";store.grants shouldBe empty
      store.persistedFenceCaptures() shouldBe Set("capture")
      store.versionedPermitForOldOwner("old") shouldBe Some(permit->binding)
      val c=DriverManager.getConnection(url)
      try {val q=c.prepareStatement("SELECT source_uuid,database_name,epoch,owner_token,version,operation_id,action,table_name,generation FROM pekko_cdc_b_fence_v2 WHERE permit_id=?")
        try {q.setString(1,permit.id);val r=q.executeQuery();try {r.next() shouldBe true;r.getString(1) shouldBe uuid;r.getLong(3) shouldBe 7L;r.getLong(5) shouldBe 13L;r.next() shouldBe false}finally r.close()}finally q.close()}finally c.close()
      release(binding).id shouldBe permit.id
      intercept[IllegalArgumentException](release(binding.copy(epoch=8L)))
      store.beginOwner("new",7L,Some(owner.copy(bootId="new-boot"))) shouldBe true
      store.recordManagedManifest("new",manifest,7L)
      store.recoveryOnlyCaptures("new") shouldBe Map("capture"->request.taskId)
      store.claimFenceRecovery("new",Set("another-capture"),8L) shouldBe None
      store.claimFenceRecovery("new",Set("capture"),8L).map(_.task.view.taskId) shouldBe Some(request.taskId)
      store.claimedFencePermit("capture",request.taskId).map(_._2) shouldBe Some(binding)
      store.receiptDigestForPermit(permit.id,request.taskId) shouldBe None
      intercept[CdcPlatformException](store.pendingBPermit("capture",request.taskId)).code shouldBe "SOURCE_FENCE_V2_UNAVAILABLE"
      intercept[CdcPlatformException](store.authorizedBPermits("capture",b.offset,Vector(request.taskId))).code shouldBe "SOURCE_FENCE_V2_UNAVAILABLE"
      intercept[CdcPlatformException](store.authorizedBPermit(permit.id,request.taskId)).code shouldBe "SOURCE_FENCE_V2_UNAVAILABLE"
      intercept[CdcPlatformException](store.consumeBPermit(permit.id,request.taskId)).code shouldBe "SOURCE_FENCE_V2_UNAVAILABLE"
      intercept[CdcPlatformException](store.rejectBPermit(permit.id,request.taskId)).code shouldBe "SOURCE_FENCE_V2_UNAVAILABLE"
      val mark=DriverManager.getConnection(url)
      try {val q=mark.prepareStatement("UPDATE pekko_cdc_b_recovery_v1 SET consumed=TRUE WHERE permit_id=?")
        try {q.setString(1,permit.id);q.executeUpdate() shouldBe 1}finally q.close()}finally mark.close()
      intercept[IllegalArgumentException](store.claimedFencePermit("capture",request.taskId))
      val saved=DriverManager.getConnection(url)
      try {val q=saved.prepareStatement("INSERT INTO pekko_cdc_b_receipt_v2 VALUES(?,?,?,?,?,?,?,?,?,?,?)")
        try {Vector[Any](permit.id,request.taskId,"new","a"*64,14L,true,"controller@localhost","b"*64,"c"*64,"synthetic","nonce").zipWithIndex.foreach {case(v,i)=>q.setObject(i+1,v)};q.executeUpdate() shouldBe 1}finally q.close()}finally saved.close()
      store.claimedFencePermit("capture",request.taskId).exists(_._1.consumed) shouldBe true
      store.receiptDigestForPermit(permit.id,request.taskId) shouldBe Some("c"*64)
      val newIdentity=identity.copy(bootId="new-boot")
      val newReceipt=SnapshotIsolationReceipt("new-isolation",newIdentity,"managed-child-jvm",2,"{\"alive\":false,\"automaticRestart\":false}")
      val newSigned=sign(JsObject("guardian"->guardian.toJson,"receipt"->newReceipt.toJson),keys.getPrivate).compactPrint
      val newProof=CdcManagedIsolation.verify(owner.copy(bootId="new-boot"),manifest,guardian,
        SnapshotWorkerLaunch("new-boot","profile","retired",Some(newIdentity),Some("new-isolation"),Some(newSigned)),
        SnapshotOwner(newIdentity,"db_drained",Some("new-isolation"),2,Some(2),None),Map("source:3306"->("uuid",Set("sdk"))))
      loseCommitReply.set(true)
      intercept[CdcExecutionException](store.freezeVersionedOwner("new",newProof,9L)).failure.code shouldBe ExecutionFailure.CommitUnknown
      store.freezeVersionedOwner("new",newProof,10L)
      store.ownerStatus shouldBe "UNKNOWN"
      store.grants.map(_._3) shouldBe Vector("UNKNOWN")
      intercept[IllegalArgumentException](store.freezeVersionedOwner("new",proof,11L))
      store.beginOwner("replacement",12L,Some(owner.copy(bootId="replacement"))) shouldBe false
      val successor=owner.copy(bootId="successor",pid=456,startIdentity="successor-start")
      val successorId=SnapshotOwnerIdentity("successor","deployment",456,"successor-start")
      val successorManifest=s"{\"version\":1,\"accounts\":[{\"endpoint\":\"source:3306\",\"serverUuid\":\"$uuid\",\"user\":\"controller\"}]}"
      def fresh(ctx:WorkerAttestationContext,nonce:String):VerifiedWorkerAttestation={
        val request=JsObject("method"->JsString("ATTEST_WORKER"),"args"->JsObject("nonce"->JsString(nonce),
          "identity"->successorId.toJson,"profile"->JsString("profile"),"context"->ctx.toJson))
        val result=JsObject("nonce"->JsString(nonce),"identity"->successorId.toJson,"profile"->JsString("profile"),
          "context"->ctx.toJson,"provider"->JsString("managed-child-jvm"),"alive"->JsBoolean(true))
        val envelope=sign(JsObject("request"->request,"guardian"->guardian.toJson,"ok"->JsBoolean(true),"result"->result),keys.getPrivate).compactPrint
        verifyWorkerAttestation(envelope,guardian,successorId,"profile",ctx,nonce)
      }
      store.admitFrozenSuccessor("new","successor",successor,successorManifest,fresh,12L)
      store.admitFrozenSuccessor("new","successor",successor,successorManifest,fresh,12L)
      store.ownerAttempt shouldBe Some("new"->"UNKNOWN")
      store.grants.map(_._3) shouldBe Vector("UNKNOWN")
      intercept[IllegalArgumentException](store.admitFrozenSuccessor("new","other",successor,successorManifest,fresh,12L))
      val noGrant=DriverManager.getConnection(url)
      try {val q=noGrant.createStatement();try {
        q.executeUpdate("UPDATE pekko_cdc_grants_v1 SET status='RELEASED' WHERE status='UNKNOWN'") shouldBe 1
      }finally q.close()}finally noGrant.close()
      store.releaseEmptyDrainedOwner("new",newProof,12L) shouldBe false
      store.ownerStatus shouldBe "UNKNOWN"
      val changed=DriverManager.getConnection(url)
      try {val q=changed.prepareStatement("UPDATE pekko_cdc_b_receipt_v2 SET receipt_digest=? WHERE permit_id=?")
        try {q.setString(1,"d"*64);q.setString(2,permit.id);q.executeUpdate() shouldBe 1}finally q.close()}finally changed.close()
      intercept[IllegalArgumentException](store.freezeVersionedOwner("new",newProof,13L))
      intercept[IllegalArgumentException](store.admitFrozenSuccessor("new","successor",successor,successorManifest,fresh,13L))
      store.ownerStatus shouldBe "UNKNOWN"
      val remove=DriverManager.getConnection(url)
      try {val q=remove.prepareStatement("DELETE FROM pekko_cdc_b_fence_v2 WHERE permit_id=?")
        try {q.setString(1,permit.id);q.executeUpdate() shouldBe 1}finally q.close()}finally remove.close()
      withClue("missing sidecar must not silently become a legacy permit") {
        intercept[CdcPlatformException](store.authorizedBPermit(permit.id,request.taskId))
        intercept[CdcPlatformException](store.versionedPermitForOldOwner("old"))
        store.persistedFenceCaptures() shouldBe Set("capture")
      }
    }
  }
}
