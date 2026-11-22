package cn.xuyinyin.magic.connectors.mysql.source.capture

import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.{Connection,DriverManager}
import java.util.UUID
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Retention component probe only; no Source/Journal/Sink end-to-end claim. */
class CaptureGcRetentionMySqlSpec extends AnyWordSpec with Matchers {
  "durable GC protection" should {
    "retain slow requirements across reconnect and read actual D rather than the newest ACK" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_CAPTURE_GC_RETENTION").contains("1"))cancel("dedicated MySQL required")
      require(sys.env.get("CDC_LAB_EXCLUSIVE_LEASE").contains("capture-archive"))
      Class.forName("com.mysql.cj.jdbc.Driver")
      val base=sys.env("CAPTURE_STATE_URL")
      require(base.contains(":25822/pekko_test_shared_state"))
      val user=sys.env("CDC_WINDOW_MYSQL_USER");val password=sys.env("CDC_WINDOW_MYSQL_PASSWORD")
      val suffix=UUID.randomUUID().toString.replace("-","").take(12)
      val schema="pekko_test_gc_retention_"+suffix;val capture="gc_"+suffix
      val admin=DriverManager.getConnection(base,user,password)
      try {val q=admin.createStatement();try q.execute("CREATE DATABASE "+schema)finally q.close()}finally admin.close()
      val url=base.replace("pekko_test_shared_state",schema)
      def open():Connection=DriverManager.getConnection(url,user,password)
      def exec(sql:String):Unit={val c=open();try {val q=c.createStatement();try q.execute(sql)finally q.close()}finally c.close()}
      exec("CREATE TABLE sdk_offsets(id INT PRIMARY KEY,offset_key LONGTEXT,offset_val LONGTEXT) ENGINE=InnoDB")
      val store=new MySQLCaptureStore(()=>open(),capture,"source-lineage",1000000);store.initialize()
      store.rememberProfile("{\"database\":\"db\"}")
      val first="{\"file\":\"binlog.000001\",\"pos\":100}"
      val second="{\"file\":\"binlog.000001\",\"pos\":200}"
      val actual=store.coverSequence(first,Vector(1L));val newest=store.coverSequence(second,Vector(1L))
      val key="[\""+capture+"\",{\"server\":\""+capture+"\"}]"
      val c=open();try {val q=c.prepareStatement("INSERT INTO sdk_offsets VALUES(1,?,?)")
        try {q.setString(1,key);q.setString(2,first);q.executeUpdate()}finally q.close()}finally c.close()
      val retention=new CaptureGcRetention(()=>open(),store,"sdk_offsets");retention.initialize()
      retention.protect(CaptureGcPin("consumer:slow","CONSUMER",1,0)) shouldBe 1L
      retention.protect(CaptureGcPin("consumer:slow","CONSUMER",1,0)) shouldBe 1L
      retention.protect(CaptureGcPin("consumer:fast","CONSUMER",newest,0)) shouldBe 2L
      intercept[IllegalArgumentException](retention.protect(CaptureGcPin("consumer:slow","CONSUMER",newest,1)))
      val recovered=new CaptureGcRetention(()=>open(),store,"sdk_offsets");recovered.initialize()
      recovered.pin("consumer:slow").get.requiredFrom shouldBe 1L
      val view=recovered.inspect()
      view.epoch shouldBe 2L;view.requiredFrom shouldBe 1L;view.actualAck shouldBe Some(actual)
      view.actualAck should not be Some(newest)
      view.blockers should contain ("CONSUMER_DISCOVERY_INCOMPLETE")
      view.blockers should contain ("RECOVERY_CERTIFICATES_REQUIRED")
      val schemaRow=store.rememberSchema("orders","{}")
      store.registerAnchored("new-table","orders","g1",schemaRow.id,first)
      recovered.inspect().blockers should contain ("UNKNOWN_B")
      store.verifiedOffsetSequence(first) shouldBe actual
      println(s"GC_RETENTION_COMPONENT schema=$schema actualD=$actual latestAck=$newest slowFrom=1 epoch=2 unknownBBlocked=true deletionExecuted=false")
    }
  }
}
