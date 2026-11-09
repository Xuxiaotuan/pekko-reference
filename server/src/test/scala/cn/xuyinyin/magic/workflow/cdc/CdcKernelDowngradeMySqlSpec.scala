package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.connectors.mysql.source.{MySQLCdcSourceConfig,MySQLCdcStateConfig}
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.DriverManager
import java.util.UUID
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Real MySQL startup guard; no Reader or Sink execution. */
class CdcKernelDowngradeMySqlSpec extends AnyWordSpec with Matchers {
  "direct unmanaged Kernel startup" should {
    "reject a retained v2 capture before opening a Reader" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CDC_JOURNAL_FENCE").contains("1"))cancel("dedicated MySQL fixture required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").exists(_.nonEmpty))
      val url=sys.env("CDC_MANAGED_TEST_JDBC_URL");require(url.contains("/pekko_test_"))
      val user=sys.env("CDC_MANAGED_TEST_USER");val password=sys.env("CDC_MANAGED_TEST_PASSWORD")
      val uuid=sys.env("CAPTURE_EXPECT_STATE_UUID");val suffix=UUID.randomUUID().toString.replace("-","").take(12)
      val capture="guard_"+suffix;val store=new CdcTaskStore(()=>DriverManager.getConnection(url,user,password),CdcPlatformLimits())
      store.initialize()
      val c=DriverManager.getConnection(url,user,password)
      try {val q=c.prepareStatement("INSERT INTO pekko_cdc_b_recovery_v1(owner_attempt,permit_id,task_id,capture_id,offset_json,marker_present,table_name,generation,operation_id,signed_proof,created_at,consumed,proof_version) VALUES(?,?,?,?,?,FALSE,'orders','g1','op','proof',1,TRUE,2)")
        try {q.setString(1,"guard-"+suffix);q.setString(2,"permit-"+suffix);q.setString(3,"a"*64);q.setString(4,capture);q.setString(5,"{}");q.executeUpdate()}finally q.close()
      }finally c.close()
      val control=MySQLCdcStateConfig(url,user,password,"capture_offsets","capture_history",0)
      val source=MySQLCdcSourceConfig(capture,"127.0.0.1",25521,"unused_source","pekko_capture_control","never_connect","unused",123456,128,100)
      val settings=CdcCaptureSettings(capture,source,control.copy(username="unused_sdk"),control,
        "70df9caa-1b7c-4082-9b92-470fbdae16c3",uuid)
      val kit=ActorTestKit("cdc-kernel-guard-"+suffix)
      try {
        for(cfg<-Vector(settings,settings.copy(sourceFenceV2=true),settings.copy(id="other_"+suffix))){
          val kernel=new CdcBootstrapKernel(CdcPlatformSettings(Vector(cfg),CdcPlatformLimits()),kit.system,managed=false)
          try {val e=intercept[IllegalArgumentException](kernel.start());e.getMessage should include ("persisted source fence cannot be downgraded")}
          finally kernel.requestStop()
        }
      }finally kit.shutdownTestKit()
    }
  }
}
