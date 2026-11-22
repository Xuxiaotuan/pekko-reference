package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.DriverManager
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class BootstrapFenceSpec extends AnyWordSpec with Matchers {
  "bootstrap source fences" should {
    "require durable original prefix coverage and retain immutable marker meaning" taggedAs ExternalIntegration in {
      if (!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1")) cancel("dedicated MySQL required")
      val store = new MySQLCaptureStore(() => DriverManager.getConnection(sys.env("CAPTURE_STATE_URL"),
        sys.env("CDC_WINDOW_MYSQL_USER"), sys.env("CDC_WINDOW_MYSQL_PASSWORD")), "fence_" + System.nanoTime(), "lineage", 1000000)
      store.initialize()
      val schema = store.rememberSchema("orders", "{}")
      store.register("s", "orders", "g", schema.id)
      store.bind("s", CapturePoint("mysql-bin.000001", 10, 0), "{}", false)
      store.registerFence("low", "s", "LOW", "{\"window\":1}")
      val n = store.bindFence("low", CapturePoint("mysql-bin.000001", 20, 0), "{}")
      store.fence("low").get.covered shouldBe false
      intercept[IllegalArgumentException](store.readThrough("s", 0, "low", 10))
      store.cover("{\"file\":\"mysql-bin.000001\",\"pos\":20}", Vector(n))
      store.fence("low").get.covered shouldBe true
      store.readThrough("s", 0, "low", 10) shouldBe empty
      store.bindFence("low", CapturePoint("mysql-bin.000001", 20, 0), "{}") shouldBe n
      intercept[IllegalArgumentException](store.registerFence("low", "s", "HIGH", "{}"))
      intercept[IllegalArgumentException](store.bindFence("low", CapturePoint("mysql-bin.000001", 21, 0), "{}"))
      store.registerFence("late", "s", "LOW", "{}")
      store.bind("s", CapturePoint("mysql-bin.000001",30,0), "{}",true)
      val late=store.bindFence("late",CapturePoint("mysql-bin.000001",40,0),"{}")
      store.cover("{\"file\":\"mysql-bin.000001\",\"pos\":40}",Vector(late))
      store.fence("late").get.obsolete shouldBe true
      store.fence("late").get.covered shouldBe false
      intercept[IllegalArgumentException](store.readThrough("s",0,"late",10))
    }
  }
}
