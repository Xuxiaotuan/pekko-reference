package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.testkit.STSpec
import java.util.concurrent.atomic.AtomicInteger

class CaptureReleaseInspectionSpec extends STSpec {
  "release inspection" should {
    "read cached evidence without stopping a capture or opening JDBC" in {
      val stopped=new AtomicInteger();val opened=new AtomicInteger()
      val store=new MySQLCaptureStore(()=>{opened.incrementAndGet();throw new AssertionError("inspection opened JDBC")},"inspect","lineage",1024)
      val capture=new SharedMySQLCapture(MySQLCdcSourceConfig("inspect","localhost",3306,"db","a","user","secret",10001,128,100),
        MySQLCdcStateConfig("jdbc:h2:mem:inspect","sa","","offsets","history",0),store,
        observe=(kind,_)=>if(kind=="CAPTURE_STOP_REQUESTED")stopped.incrementAndGet())
      try {
        capture.releaseEvidence.reusable shouldBe false
        stopped.get() shouldBe 0
        opened.get() shouldBe 0
        capture.cachedStartupEvidence.phase shouldBe "NEW"
      }finally capture.close()
    }
  }
}
