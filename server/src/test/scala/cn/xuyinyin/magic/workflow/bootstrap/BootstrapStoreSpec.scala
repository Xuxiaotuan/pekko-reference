package cn.xuyinyin.magic.workflow.bootstrap

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.DriverManager
import spray.json._

class BootstrapStoreSpec extends AnyWordSpec with Matchers {
  "immutable initialization material" should {
    "seal pages once, reject content reuse and recheck stored bytes" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1"))cancel("dedicated MySQL required")
      val s=new BootstrapStore(()=>DriverManager.getConnection(sys.env("CAPTURE_STATE_URL"),sys.env("CDC_WINDOW_MYSQL_USER"),sys.env("CDC_WINDOW_MYSQL_PASSWORD")),"bootstrap_"+System.nanoTime(),100000)
      s.initialize("definition")
      s.register("window","{\"lower\":null,\"upper\":100}")
      val rows=Vector(JsObject("id"->JsNumber(1),"v"->JsString("a")))
      s.rawPage("window",0,rows)
      s.rawPage("window",0,rows)
      intercept[IllegalArgumentException](s.rawPage("window",0,Vector(JsObject("id"->JsNumber(2)))))
      val m=s.seal("window","{}",rows,10)
      s.manifest("window") shouldBe Some(m)
      s.page("window","FINAL",0) shouldBe rows
      intercept[IllegalArgumentException](s.seal("window","{}",Vector(JsObject("id"->JsNumber(3))),10))
    }
    "refuse to reset capacity when a material registry disappears" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1"))cancel("dedicated MySQL required")
      def open()=DriverManager.getConnection(sys.env("CAPTURE_STATE_URL"),sys.env("CDC_WINDOW_MYSQL_USER"),sys.env("CDC_WINDOW_MYSQL_PASSWORD"))
      val id="lost_registry_"+System.nanoTime();val s=new BootstrapStore(()=>open(),id,2000)
      s.initialize("definition");s.register("w","{}");s.rawPage("w",0,Vector(JsObject("id"->JsNumber(1),"v"->JsString("x"*900))))
      val c=open();try {val q=c.prepareStatement("DELETE FROM pekko_bootstrap_storage WHERE bootstrap_id=?");try {q.setString(1,id);q.executeUpdate()}finally q.close()}finally c.close()
      intercept[IllegalArgumentException](new BootstrapStore(()=>open(),id,2000).initialize("definition"))
    }
  }
}
