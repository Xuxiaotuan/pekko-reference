package cn.xuyinyin.magic.connectors.mysql.source

import cn.xuyinyin.magic.connectors.mysql.source.capture._
import cn.xuyinyin.magic.cdc._
import cn.xuyinyin.magic.tags.ExternalIntegration
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.sql.DriverManager
import spray.json._

class SharedCaptureStoreSpec extends AnyWordSpec with Matchers {
  "real capture transactions" should {
    "reject a removed subscription behind an otherwise valid D proof" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1"))cancel("isolated MySQL required")
      val url=sys.env("CAPTURE_STATE_URL");val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val store=new MySQLCaptureStore(()=>DriverManager.getConnection(url,user,password),"registry-"+System.nanoTime(),"uuid",100000)
      store.initialize();val schema=store.rememberSchema("orders","{}")
      store.register("r","orders","g",schema.id);store.bind("r",CapturePoint("mysql-bin.000001",100,0),"{}",false)
      val offset="""{"file":"mysql-bin.000001","pos":110}"""
      store.cover(offset,Vector(store.barrierSequence("r",false).get));store.verifyOffset(offset)
      val c=DriverManager.getConnection(url,user,password)
      try {val q=c.prepareStatement("DELETE FROM pekko_capture_subscriptions WHERE capture_id=?")
        try {q.setString(1,store.captureId);q.executeUpdate()}finally q.close()
      }finally c.close()
      intercept[IllegalArgumentException](store.verifyOffset(offset))
    }
    "bind one immutable B and enforce historical routing, generation and capacity" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1")) cancel("isolated MySQL required")
      val url=sys.env("CAPTURE_STATE_URL"); val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val store=new MySQLCaptureStore(()=>DriverManager.getConnection(url,user,password),"store-"+System.nanoTime(),"uuid",100000)
      store.initialize()
      val profile=store.rememberProfile("""{"database":"db","protocol":1}""")
      store.rememberProfile("""{"protocol":1,"database":"db"}""") shouldBe profile
      intercept[IllegalArgumentException](store.rememberProfile("""{"database":"other","protocol":1}"""))
      val schema=store.rememberSchema("orders","""{"columns":[{"name":"id","jdbcType":-5,"typeName":"BIGINT"}],"primaryKeyColumnNames":["id"]}""")
      store.register("req","orders","g1",schema.id).status shouldBe "B_PENDING"
      val b=CapturePoint("mysql-bin.000001",100,0)
      store.bind("req",b,"{}",stop=false).status shouldBe "CAPTURE_READY"
      store.bind("req",b,"{}",stop=false).b shouldBe Some(b)
      intercept[IllegalArgumentException](store.bind("req",CapturePoint(b.file,101,0),"{}",stop=false))
      store.subscription("req").get.initialized shouldBe false
      val protocol=MySQLCdcGroupProtocol(store.captureId,"capture-v1","uuid")
      def group(pos:Long,id:Long):MySQLCdcGroup={
        val e=MySQLCdcEnvelope(1,CdcOperation.Create,JsObject("id"->JsNumber(id)),None,Some(JsObject("id"->JsNumber(id))),
          MySQLCdcSourcePosition(store.captureId,"db","orders",false,Some(b.file),Some(pos),Some(0),Some(1)))
        MySQLCdcGroup(MySQLCdcGroup.id(protocol,e),Vector("single.c"->e))
      }
      store.append(group(99,1)) shouldBe None
      val g=group(110,2); val seq=store.append(g).get
      store.append(g) shouldBe Some(seq)
      val offset="""{"offset":{"file":"mysql-bin.000001","pos":110,"row":1}}"""
      store.cover(offset,Vector(seq))
      store.verifyOffset(offset)
      store.read("req",0,10).map(_.group) shouldBe Vector(g)
      val changed=g.copy(members=g.members.map {case(role,e)=>role->e.copy(after=Some(JsObject("id"->JsNumber(3))))})
      intercept[IllegalArgumentException](store.append(changed))
      store.bind("req",CapturePoint(b.file,120,0),"{}",stop=true)
      store.register("req2","orders","g2",schema.id)
      store.bind("req2",CapturePoint(b.file,150,0),"{}",stop=false)
      store.append(group(110,2)) shouldBe Some(seq)
      store.append(group(160,4)) should not be empty
      store.read("req2",0,10).map(_.group.members.head._2.key) shouldBe Vector(JsObject("id"->JsNumber(4)))
      intercept[IllegalArgumentException](store.register("req2","orders","different",schema.id))
      val c=DriverManager.getConnection(url,user,password)
      try {val q=c.prepareStatement("UPDATE pekko_capture_log SET schema_id=? WHERE capture_id=? AND kind='GROUP'")
        try {q.setString(1,"0"*64);q.setString(2,store.captureId);q.executeUpdate()}finally q.close()
      }finally c.close()
      intercept[IllegalArgumentException](store.read("req",0,10))
      intercept[IllegalArgumentException](store.verifyOffset(offset))
      val changedBinding=DriverManager.getConnection(url,user,password)
      try {val q=changedBinding.prepareStatement("UPDATE pekko_capture_subscriptions SET b_point=? WHERE capture_id=? AND request_id='req'")
        try {q.setString(1,CapturePoint(b.file,99999,0).json);q.setString(2,store.captureId);q.executeUpdate()}finally q.close()
      }finally changedBinding.close()
      intercept[IllegalArgumentException](store.subscription("req"))
      val missing=DriverManager.getConnection(url,user,password)
      try {val q=missing.prepareStatement("DELETE FROM pekko_capture_subscriptions WHERE capture_id=?")
        try {q.setString(1,store.captureId);q.executeUpdate()}finally q.close()
      }finally missing.close()
      intercept[IllegalArgumentException](store.route("orders",CapturePoint(b.file,200,0)))
    }
  }
}
