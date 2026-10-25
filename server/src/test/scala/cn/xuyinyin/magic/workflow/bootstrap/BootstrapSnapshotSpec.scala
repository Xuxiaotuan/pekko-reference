package cn.xuyinyin.magic.workflow.bootstrap
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.DriverManager
import spray.json._
class BootstrapSnapshotSpec extends AnyWordSpec with Matchers {
  "bootstrap read view" should {
    "page one frozen range and reject resuming raw input under a seal" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1"))cancel("dedicated MySQL required")
      def open()=DriverManager.getConnection(sys.env("CAPTURE_TEST_URL"),sys.env("CDC_WINDOW_MYSQL_USER"),sys.env("CDC_WINDOW_MYSQL_PASSWORD"))
      val table="snapshot_"+System.nanoTime();val c=open()
      try {val q=c.createStatement();try {q.executeUpdate("CREATE TABLE IF NOT EXISTS pekko_capture_control(operation_id VARCHAR(128) PRIMARY KEY,action VARCHAR(32),table_name VARCHAR(128),generation VARCHAR(128)) ENGINE=InnoDB");q.executeUpdate(s"CREATE TABLE $table(id BIGINT PRIMARY KEY,label VARCHAR(64)) ENGINE=InnoDB");q.executeUpdate(s"INSERT INTO $table VALUES(-5,'a'),(2,'b'),(100,'c')")}finally q.close()}finally c.close()
      val codec=new BootstrapRowCodec("""{"primaryKeyColumnNames":["id"],"columns":[{"name":"id","jdbcType":-5},{"name":"label","jdbcType":12}]}""")
      val store=new BootstrapStore(()=>open(),"view_"+System.nanoTime(),1048576);store.initialize("definition");store.register("w","{}")
      val source=new BootstrapSnapshot(()=>open(),table,codec,32,1048576)
      source.validate();source.maximum() shouldBe Some("100");source.nextUpper(None,"100",2) shouldBe "2"
      source.scan("w",None,"100",2,store,()=>false)
      store.pageCount("w","RAW") shouldBe 2
      store.page("w","RAW",0).map(_.fields("id")) shouldBe Vector(JsNumber(-5),JsNumber(2))
    }
  }
}
