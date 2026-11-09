package cn.xuyinyin.magic.workflow.bootstrap

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import cn.xuyinyin.magic.tags.ExternalIntegration
import java.sql.DriverManager
import spray.json._

class BootstrapTargetSpec extends AnyWordSpec with Matchers {
  "bootstrap target transactions" should {
    "reject tampered table and schema bindings before writing target rows or activation" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1"))cancel("dedicated MySQL required")
      def open()=DriverManager.getConnection(sys.env("CAPTURE_STATE_URL"),sys.env("CDC_WINDOW_MYSQL_USER"),sys.env("CDC_WINDOW_MYSQL_PASSWORD"))
      val table="boot_binding_"+System.nanoTime();val id="binding_"+System.nanoTime()
      val c=open();try {val q=c.createStatement();try q.executeUpdate(s"CREATE TABLE $table(id BIGINT PRIMARY KEY,label VARCHAR(64)) ENGINE=InnoDB")finally q.close()}finally c.close()
      val schema="""{"primaryKeyColumnNames":["id"],"columns":[{"name":"id","jdbcType":-5,"typeName":"BIGINT"},{"name":"label","jdbcType":12,"typeName":"VARCHAR"}]}"""
      val codec=new BootstrapRowCodec(schema);val t=new BootstrapTarget(()=>open(),table,codec,id)
      val rows=Vector(JsObject("id"->JsNumber(1),"label"->JsString("first")))
      def alter(column:String,value:String):Unit={val c=open();try {val q=c.prepareStatement(s"UPDATE pekko_bootstrap_targets SET $column=? WHERE bootstrap_id=?")
        try {q.setString(1,value);q.setString(2,id);q.executeUpdate() shouldBe 1}finally q.close()}finally c.close()}
      def count(sql:String):Long={val c=open();try {val q=c.createStatement();try {val r=q.executeQuery(sql);try {r.next();r.getLong(1)}finally r.close()}finally q.close()}finally c.close()}
      t.claim()
      alter("table_name","wrong_table")
      intercept[IllegalArgumentException](t.claim())
      intercept[IllegalArgumentException](t.applyPage("window",0,"manifest",rows))
      intercept[IllegalArgumentException](t.activate("proof"))
      count(s"SELECT COUNT(*) FROM $table") shouldBe 0L
      alter("table_name",table);alter("schema_id","0"*64)
      intercept[IllegalArgumentException](t.applyPage("window",0,"manifest",rows))
      intercept[IllegalArgumentException](t.activate("proof"))
      count(s"SELECT COUNT(*) FROM $table") shouldBe 0L
      alter("schema_id",codec.schemaId)
      t.applyPage("window",0,"manifest",rows) shouldBe true
      t.activate("proof")
      count(s"SELECT COUNT(*) FROM $table") shouldBe 1L
    }
    "reserve an empty target and arbitrate immutable page replays" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1"))cancel("dedicated MySQL required")
      def open()=DriverManager.getConnection(sys.env("CAPTURE_STATE_URL"),sys.env("CDC_WINDOW_MYSQL_USER"),sys.env("CDC_WINDOW_MYSQL_PASSWORD"))
      val table="boot_target_"+System.nanoTime()
      val c=open();try {val q=c.createStatement();try q.executeUpdate(s"CREATE TABLE $table(id BIGINT PRIMARY KEY,label VARCHAR(64)) ENGINE=InnoDB")finally q.close()}finally c.close()
      val schema="""{"primaryKeyColumnNames":["id"],"columns":[{"name":"id","jdbcType":-5,"typeName":"BIGINT"},{"name":"label","jdbcType":12,"typeName":"VARCHAR"}]}"""
      val codec=new BootstrapRowCodec(schema)
      val t=new BootstrapTarget(()=>open(),table,codec,"boot_"+System.nanoTime())
      t.claim();t.claim()
      intercept[IllegalArgumentException](new BootstrapTarget(()=>open(),table,codec,"other").claim())
      val rows=Vector(JsObject("id"->JsNumber(1),"label"->JsString("first")))
      t.applyPage("window",0,"manifest",rows) shouldBe true
      t.applyPage("window",0,"manifest",rows) shouldBe false
      intercept[IllegalArgumentException](t.applyPage("window",0,"manifest",Vector(JsObject("id"->JsNumber(2),"label"->JsString("changed")))))
      t.verifyPage("window",0,"manifest",rows)
      t.activate("proof");t.activate("proof")
      intercept[IllegalArgumentException](t.activate("different"))
    }
    "reject an actual TIMESTAMP target for a DATETIME sealed source" taggedAs ExternalIntegration in {
      if(!sys.env.get("RUN_SHARED_CAPTURE_MATRIX").contains("1"))cancel("dedicated MySQL required")
      def open()=DriverManager.getConnection(sys.env("CAPTURE_STATE_URL"),sys.env("CDC_WINDOW_MYSQL_USER"),sys.env("CDC_WINDOW_MYSQL_PASSWORD"))
      val table="boot_temporal_"+System.nanoTime();val c=open()
      try {val q=c.createStatement();try q.executeUpdate(s"CREATE TABLE $table(id BIGINT PRIMARY KEY,t TIMESTAMP(6)) ENGINE=InnoDB")finally q.close()}finally c.close()
      val codec=new BootstrapRowCodec("""{"primaryKeyColumnNames":["id"],"columns":[{"name":"id","jdbcType":-5,"typeName":"BIGINT"},{"name":"t","jdbcType":93,"typeName":"DATETIME","length":6}]}""")
      intercept[IllegalArgumentException](new BootstrapTarget(()=>open(),table,codec,"bad_temporal_"+System.nanoTime()).claim())
    }
  }
}
