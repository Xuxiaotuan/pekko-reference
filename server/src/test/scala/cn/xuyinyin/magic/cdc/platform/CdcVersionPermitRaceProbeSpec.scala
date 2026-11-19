package cn.xuyinyin.magic.cdc.platform

import cn.xuyinyin.magic.tags.ExternalIntegration
import java.nio.file.{Files,Path}
import java.sql.{Connection,DriverManager}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

/** A synthetic permit exercises the production authorization method, not the guardian path. */
class CdcVersionPermitRaceProbeSpec extends AnyWordSpec with Matchers {
  "the current recovery permit" should {
    "expose whether a later source version change affects authorization" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CDC_VERSION_PERMIT_RACE").contains("1"))cancel("private dual MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("version-permit-race"))
      val src=sys.env("CAPTURE_TEST_URL");val dst=sys.env("CAPTURE_STATE_URL")
      require(src.contains("pekko_test_shared_wire") && dst.contains("pekko_test_shared_state") && !src.contains(":3306/"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      def open(url:String)=DriverManager.getConnection(url,user,password)
      def execute(c:Connection,sql:String):Unit={val q=c.createStatement();try {q.execute(sql);()}finally q.close()}
      def scalar(c:Connection,sql:String):String={val q=c.createStatement();try {val r=q.executeQuery(sql);try {require(r.next());r.getString(1)}finally r.close()}finally q.close()}
      val suffix=UUID.randomUUID().toString.replace("-","").take(10);val v="probe_version_"+suffix
      val source=open(src);val admin=open(dst)
      val stateSchema="pekko_test_version_race_"+suffix
      execute(admin,s"CREATE DATABASE $stateSchema")
      val isolated=dst.replace("pekko_test_shared_state",stateSchema)
      val state=open(isolated)
      try {
        execute(source,s"CREATE TABLE $v(id INT PRIMARY KEY,version BIGINT NOT NULL) ENGINE=InnoDB")
        execute(source,s"INSERT INTO $v VALUES(1,1)")
        val store=new CdcTaskStore(()=>open(isolated),CdcPlatformLimits(snapshotSteps=1,tailSteps=1));store.initialize()
        val managed=CdcManagedOwner("boot-"+suffix,"deployment","host","guardian","key",123L,"start","profile")
        val attempt="owner-"+suffix;store.beginOwner(attempt,1L,Some(managed)) shouldBe true
        val task=store.submit(CdcTaskRequest("r-"+suffix,"capture", "rows_"+suffix,"g1","target_"+suffix),2L).task
        store.claim(attempt,"SNAPSHOT",Set("capture"),3L).isDefined shouldBe true
        source.setAutoCommit(false)
        val checked=scalar(source,s"SELECT version FROM $v WHERE id=1 FOR UPDATE")
        val permit="permit-"+suffix
        val q=state.prepareStatement("INSERT INTO pekko_cdc_b_recovery_v1(owner_attempt,permit_id,task_id,capture_id,offset_json,marker_present,table_name,generation,operation_id,signed_proof,created_at,claimed_by,consumed) VALUES(?,?,?,'capture','{}',FALSE,?,'g1','op','synthetic',1,?,FALSE)")
        try {q.setString(1,"old-"+suffix);q.setString(2,permit);q.setString(3,task.taskId);q.setString(4,task.table);q.setString(5,attempt);q.executeUpdate()}finally q.close()
        source.commit() // Model loss/release of the source lock after the state row commits.
        val before=store.authorizedBPermit(permit,task.taskId)
        execute(source,s"UPDATE $v SET version=version+1 WHERE id=1")
        source.commit()
        val after=scalar(source,s"SELECT version FROM $v WHERE id=1")
        val acceptedAfterChange=store.authorizedBPermit(permit,task.taskId)
        before shouldBe true;after.toLong should be > checked.toLong
        val out=Path.of(sys.env("CDC_VERSION_PERMIT_RACE_EVIDENCE"));Files.createDirectories(out)
        val result=JsObject("checkedSourceVersion"->JsString(checked),"currentSourceVersion"->JsString(after),
          "authorizedBefore"->JsBoolean(before),"authorizedAfterVersionChange"->JsBoolean(acceptedAfterChange),
          "sourceUuid"->JsString(scalar(source,"SELECT @@server_uuid")),"stateUuid"->JsString(scalar(state,"SELECT @@server_uuid")),
          "scope"->JsString("real MySQL source/state and production permit authorization; synthetic permit, owner, source version; no guardian, Reader, Journal, Sink, actual D or restart"))
        Files.writeString(out.resolve("result.json"),result.prettyPrint)
        withClue("current authorization ignores the synthetic source version"){acceptedAfterChange shouldBe true}
      }finally {state.close();admin.close();source.close()}
    }
  }
}
