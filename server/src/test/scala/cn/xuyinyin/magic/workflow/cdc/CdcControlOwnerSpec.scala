package cn.xuyinyin.magic.workflow.cdc

import cn.xuyinyin.magic.testkit.STSpec
import java.sql.DriverManager
import java.util.UUID

class CdcControlOwnerSpec extends STSpec {
  "control owner inventory" should {
    "refuse to replace a missing owner row when retained task evidence exists" in {
      val url="jdbc:h2:mem:cdc_owner_missing_"+UUID.randomUUID().toString.replace('-','_')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
      val store=new CdcTaskStore(()=>DriverManager.getConnection(url),CdcPlatformLimits())
      store.initialize();store.beginOwner("previous",1L) shouldBe true
      store.submit(CdcTaskRequest("request","capture","rows","g1","target"),2L)
      val c=DriverManager.getConnection(url)
      try {val q=c.createStatement();try q.executeUpdate("DELETE FROM pekko_cdc_owner_v1")finally q.close()}finally c.close()
      intercept[CdcPlatformException](store.initialize()).code shouldBe "CONTROL_OWNER_MISSING"
    }
  }
}
