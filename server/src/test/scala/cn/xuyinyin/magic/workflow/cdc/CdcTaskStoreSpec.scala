package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.testkit.STSpec
import java.sql.DriverManager
import java.util.UUID

class CdcTaskStoreSpec extends STSpec {
  private def store():CdcTaskStore={
    val url="jdbc:h2:mem:cdc_tasks_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    val s=new CdcTaskStore(()=>DriverManager.getConnection(url),CdcPlatformLimits(snapshotSteps=1,tailSteps=1));s.initialize();s
  }
  private def request(id:String)=CdcTaskRequest(id,"capture","rows_"+id,"g1","target_"+id)
  "durable CDC task index" should {
    "refuse pre-execution cancellation while a B recovery permit is pending" in {
      val url="jdbc:h2:mem:cdc_b_cancel_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val s=new CdcTaskStore(()=>DriverManager.getConnection(url),CdcPlatformLimits());s.initialize()
      val managed=CdcManagedOwner("boot","d","host","guardian","key",123,"start","profile")
      s.beginOwner("new-owner",1L,Some(managed)) shouldBe true
      val task=s.submit(request("cancel"),2L).task
      val c=DriverManager.getConnection(url)
      try {val q=c.prepareStatement("INSERT INTO pekko_cdc_b_recovery_v1(owner_attempt,permit_id,task_id,capture_id,offset_json,marker_present,table_name,generation,operation_id,signed_proof,created_at,claimed_by,consumed) VALUES('old','permit',?,'capture','{}',FALSE,?,'g1','op','proof',1,'new-owner',FALSE)")
        try {q.setString(1,task.taskId);q.setString(2,task.table);q.executeUpdate()}finally q.close()}finally c.close()
      intercept[CdcPlatformException](s.cancel(task.taskId,3L)).code shouldBe "CANCEL_NOT_PERMITTED"
      s.get(task.taskId).get.desiredState shouldBe "RUNNING"
      s.get(task.taskId).get.cancelAllowed shouldBe false
      s.listAfter(None,100).items.find(_.taskId==task.taskId).get.cancelAllowed shouldBe false
      s.endOwner("new-owner",confirmed=true,4L) shouldBe false
      s.ownerStatus shouldBe "UNKNOWN"
    }
    "preserve the identity of duplicate requests and reject a changed definition" in {
      val s=store();val first=s.submit(request("a"),1L);first.created shouldBe true
      s.submit(request("a"),2L).task.taskId shouldBe first.task.taskId
      s.submit(request("a"),2L).created shouldBe false
      intercept[CdcPlatformException](s.submit(request("a").copy(table="other"),3L)).code shouldBe "CONFLICT"
    }
    "cancel an unexecuted task without claiming a Journal or source boundary" in {
      val s=store();val first=s.submit(request("a"),1L).task
      val canceled=s.cancel(first.taskId,2L)
      canceled.phase shouldBe "CANCELED";canceled.initializationEvidence shouldBe None;canceled.cancellationEvidence shouldBe Some("PREEXEC_CANCEL")
      canceled.executionId shouldBe None;canceled.journalRevision shouldBe 0L
      s.cancel(first.taskId,3L).controlRevision shouldBe canceled.controlRevision
    }
    "page cold records without materializing their execution resources" in {
      val s=store();(1 to 7).foreach(i=>s.submit(request(i.toString),i.toLong))
      val a=s.listAfter(None,3);a.items.size shouldBe 3;a.nextAfter should not be empty
      val b=s.listAfter(a.nextAfter,3);val c=s.listAfter(b.nextAfter,3)
      (a.items++b.items++c.items).map(_.taskId).distinct.size shouldBe 7
      s.grants shouldBe empty
    }
    "retain unknown grants and refuse a new owner after an unclean stop" in {
      val s=store();s.beginOwner("one",1L) shouldBe true;s.submit(request("a"),2L)
      val grant=s.claim("one","SNAPSHOT",Set("capture"),3L).get
      s.finishUnknown(grant,"INJECTED_UNKNOWN",4L)
      s.grants.head._3 shouldBe "UNKNOWN"
      s.claim("one","SNAPSHOT",Set("capture"),5L) shouldBe None
      s.beginOwner("two",6L) shouldBe false
      s.get(grant.task.view.taskId).get.releaseStatus shouldBe "UNKNOWN"
    }
    "fence managed admission as soon as a grant becomes unknown" in {
      val s=store();val managed=CdcManagedOwner("boot","d","host","guardian","key",123,"start","profile")
      s.beginOwner("managed",1L,Some(managed)) shouldBe true;s.submit(request("a"),2L)
      val grant=s.claim("managed","SNAPSHOT",Set("capture"),3L).get
      s.finishUnknown(grant,"local_release_unconfirmed",4L)
      s.ownerStatus shouldBe "UNKNOWN"
      s.managedOwnerNeedsIsolation(managed) shouldBe true
      s.managedOwnerNeedsIsolation(managed.copy(bootId="other")) shouldBe false
      intercept[IllegalArgumentException](s.managedOwnerNeedsIsolation(managed.copy(profileHash="wrong")))
      intercept[IllegalArgumentException](s.managedOwnerNeedsIsolation(managed.copy(pid=124)))
      s.claim("managed","TAIL",Set("capture"),5L) shouldBe None
      s.beginOwner("replacement",6L) shouldBe false
    }
    "park idle streaming tasks and wake them from a bounded durable L dispatch" in {
      val url="jdbc:h2:mem:cdc_dispatch_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val s=new CdcTaskStore(()=>DriverManager.getConnection(url),CdcPlatformLimits(snapshotSteps=1,tailSteps=1));s.initialize()
      val setup=DriverManager.getConnection(url)
      try {val q=setup.createStatement();try q.execute("CREATE TABLE pekko_capture_log(capture_id VARCHAR(64),seq BIGINT,kind VARCHAR(32),subscription_id CHAR(64),PRIMARY KEY(capture_id,seq))")finally q.close()}finally setup.close()
      s.beginOwner("one",1L) shouldBe true
      val task=s.submit(request("a"),2L).task
      val first=s.claim("one","SNAPSHOT",Set("capture"),3L).get
      val progress=cn.xuyinyin.magic.workflow.bootstrap.BootstrapProgress(revision=1,phase="STREAMING",executionId="test-execution")
      s.finish(first,progress,Some("test-only-proof"),"ADMISSION_READY",true,true,1L,4L)
      s.claim("one","TAIL",Set("capture"),5L) shouldBe None
      val input=DriverManager.getConnection(url)
      try {val q=input.prepareStatement("INSERT INTO pekko_capture_log VALUES('capture',1,'GROUP',?)");try {q.setString(1,task.taskId);q.executeUpdate()}finally q.close()}finally input.close()
      s.dispatch("one",Set("capture"),128,6L).caughtUp shouldBe true
      val tail=s.claim("one","TAIL",Set("capture"),7L).get
      tail.task.view.taskId shouldBe task.taskId
      s.finish(tail,progress.copy(revision=2,deltaAfter=1),Some("test-only-proof"),"ADMISSION_READY",true,true,1L,8L)
      s.dispatch("one",Set("capture"),128,9L).frames shouldBe 0
      s.claim("one","TAIL",Set("capture"),10L) shouldBe None
      s.endOwner("one",true,11L) shouldBe true
      s.beginOwner("two",12L) shouldBe true
      s.recoverColdPage("two",None,128,13L) shouldBe None
      val recovered=s.claim("two","TAIL",Set("capture"),14L).get
      s.continuation(recovered) shouldBe None
    }
    "skip terminal and unready capture prefixes when claiming the oldest eligible task" in {
      val s=store();s.beginOwner("owner",0L)
      (1 to 80).foreach {i=>val v=s.submit(request("old"+i),1L).task;s.cancel(v.taskId,2L)}
      (1 to 80).foreach(i=>s.submit(request("waiting"+i).copy(captureId="not_ready"),1L))
      val eligible=s.submit(request("eligible"),100L).task
      s.claim("owner","SNAPSHOT",Set("capture"),101L).get.task.view.taskId shouldBe eligible.taskId
    }
  }
}
