package cn.xuyinyin.magic.workflow.bootstrap

import cn.xuyinyin.magic.cdc.MySQLCdcGroup
import cn.xuyinyin.magic.connectors.mysql.source.capture.{CapturePoint,MySQLCaptureStore}
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.{Connection,DriverManager}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

/** Real state storage with synthetic markers; this is not a Reader or Sink test. */
class BootstrapActivationFenceMySqlSpec extends AnyWordSpec with Matchers {
  "bootstrap activation fence" should {
    "bind B and covered F to the same stored subscription" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_BOOTSTRAP_FENCE_ID_PROBE").contains("1"))cancel("isolated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-archive"))
      val root=sys.env("CAPTURE_STATE_URL")
      require(root.contains(":25822/") && root.contains("pekko_test_shared_state"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val suffix=UUID.randomUUID().toString.replace("-","").take(10)
      val schema="pekko_test_fence_identity_"+suffix
      val admin=DriverManager.getConnection(root,user,password)
      try {val q=admin.createStatement();try q.execute("CREATE DATABASE "+schema)finally q.close()}finally admin.close()
      val url=root.replace("pekko_test_shared_state",schema)
      def open():Connection=DriverManager.getConnection(url,user,password)
      val store=new MySQLCaptureStore(()=>open(),"capture_"+suffix,"lineage",10000000)
      store.initialize()
      val schemaId=store.rememberSchema("orders","{}").id
      store.register("task","orders","g1",schemaId)
      val b=CapturePoint("mysql-bin.000001",100,0)
      store.bind("task",b,"{}",stop=false)
      val context=MySQLCdcGroup.canonical(JsObject("bootstrap"->JsString("task"),"window"->JsString("F"),"schema"->JsString(schemaId)))
      val lowContext=MySQLCdcGroup.canonical(JsObject("bootstrap"->JsString("task"),"window"->JsString("window"),"schema"->JsString(schemaId)))
      store.registerFence("task_L","task","LOW",lowContext)
      val low=store.bindFence("task_L",CapturePoint("mysql-bin.000001",110,0),"{}")
      store.registerFence("task_F","task","F",context)
      val f=store.bindFence("task_F",CapturePoint("mysql-bin.000001",120,0),"{}")
      store.cover("""{"file":"mysql-bin.000001","pos":125}""",Vector(low,f))
      val sub=store.subscription("task").get
      val valid=store.fence("task_F").get
      valid.covered shouldBe true
      noException should be thrownBy BootstrapActivationFence.verify("task","orders","g1",schemaId,context,sub,valid)
      val wrong=store.fence("task_L").get
      wrong.covered shouldBe true
      intercept[IllegalArgumentException](BootstrapActivationFence.verify("task","orders","g1",schemaId,context,sub,wrong))
      intercept[IllegalArgumentException](BootstrapActivationFence.verify("task","orders","g2",schemaId,context,sub,valid))
      println(s"FENCE_IDENTITY_STATE_ONLY schema=$schema b=${sub.bSequence.get} f=${valid.sequence.get} covered=true lowRejected=true generationRejected=true")
    }
  }
}
